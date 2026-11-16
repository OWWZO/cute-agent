# -*- coding: utf-8 -*-
"""文件落盘与元数据 CRUD。

- FileDB: 按 request_id(scope) 将内容写到本地目录
- FileInfoOp: 元数据增查（同 file_id 覆盖更新）
- get_file_*_url: 拼预览/下载 URL，供 Java 与前端消费
"""

import os
from pathlib import Path
from typing import List

from fastapi import UploadFile
from sqlmodel import select

from cute_tool.db.file_table import FileInfo
from cute_tool.db.db_engine import async_session_local
from cute_tool.util.file_name import normalize_stored_file_name
from cute_tool.util.log_util import timer
from cute_tool.util.path_safety import resolve_within_root, safe_path_segment


class _FileDB(object):
    """本地文件存储：按 scope 分子目录，避免不同会话文件互相覆盖。"""

    def __init__(self):
        self._work_dir = os.getenv("FILE_SAVE_PATH", "file_db_dir")
        if not os.path.exists(self._work_dir):
            os.makedirs(self._work_dir)

    async def save(self, file_name, content, scope) -> str:
        """将文本内容写入 scope 目录，返回落盘路径。"""
        # 文件名和 scope 分别清洗：前者控制单文件名，后者控制会话目录，不能把完整用户路径直接拼入落盘路径。
        if "." in file_name:
            file_name = os.path.basename(file_name)
        else:
            file_name = f"{file_name}.txt"
        file_name = normalize_stored_file_name(file_name)

        save_path = self._scope_directory(scope)
        file_path = resolve_within_root(save_path, file_name, label="file path")
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(content)
        return str(file_path)

    async def save_by_data(
        self,
        file: UploadFile,
        scope: str = None,
        relative_name: str = None,
    ) -> str:
        """将 UploadFile 二进制写入本地，返回落盘路径。"""
        # 上传流可能已被上游读取，先尝试回到开头；只有确认仍为空才走底层 file 对象的同步兜底读取。
        file_name = normalize_stored_relative_path(relative_name or file.filename)
        # 必须二进制读取；await file.read() 兼容已消费/未 seek 的 stream
        try:
            await file.seek(0)
        except Exception:
            try:
                file.file.seek(0)
            except Exception:
                pass
        file_data = await file.read()
        if not file_data:
            # fallback sync read once more after seek
            try:
                file.file.seek(0)
                file_data = file.file.read()
            except Exception:
                file_data = b""
        if not file_data:
            raise ValueError(f"uploaded file is empty: {file_name}")
        save_directory = self._scope_directory(scope)
        save_path = resolve_within_root(
            save_directory, file_name, label="file path"
        )
        save_path.parent.mkdir(parents=True, exist_ok=True)
        with open(save_path, "wb") as f:
            f.write(file_data)
        return str(save_path)

    def _scope_directory(self, scope: str | None) -> str:
        """Return the work dir for an empty scope, otherwise a safe child dir."""
        if scope is None or not str(scope).strip():
            return str(resolve_within_root(self._work_dir, label="scope"))
        raw_scope = str(scope).strip()
        if (
            "\x00" in raw_scope
            or "/" in raw_scope
            or "\\" in raw_scope
            or (len(raw_scope) >= 2 and raw_scope[1] == ":")
        ):
            raise ValueError("scope must be a single safe path segment")
        sanitized = "".join(
            char if char not in '<>:"|?*' else "_" for char in raw_scope
        )
        safe_scope = safe_path_segment(sanitized, label="scope")
        save_path = resolve_within_root(self._work_dir, safe_scope, label="scope")
        save_path.mkdir(parents=True, exist_ok=True)
        return str(save_path)


FileDB = _FileDB()


def _allowed_local_file_roots() -> list[str]:
    """Return roots whose existing local files may be registered by the file service."""
    roots: list[str] = []
    raw_root = os.getenv("FILE_SAVE_PATH", "file_db_dir")
    root = os.path.expanduser(raw_root)
    if not os.path.isabs(root):
        root = os.path.join(os.getcwd(), root)
    roots.append(root)
    roots.append(os.path.join(os.getcwd(), "skilloutput"))
    for env_name in ("CUTE_DOCGEN_OUTPUT_DIR", "CUTE_DOCGEN_UPLOAD_DIR"):
        value = os.getenv(env_name)
        if value:
            roots.append(value)
    return [os.path.realpath(os.path.abspath(item)) for item in roots]


def _is_path_under_allowed_root(path: str) -> bool:
    resolved = os.path.realpath(os.path.abspath(path))
    for root in _allowed_local_file_roots():
        try:
            if os.path.commonpath([resolved, root]) == root:
                return True
        except ValueError:
            continue
    return False


def normalize_stored_relative_path(file_name: str) -> str:
    """保留目录结构的相对路径归一化；拒绝绝对路径、盘符、UNC 与 .. 逃逸。"""
    raw = (file_name or "").strip().replace("\\", "/")
    while raw.startswith("./"):
        raw = raw[2:]
    if (
        not raw
        or "\x00" in raw
        or raw.startswith("/")
        or raw.startswith("~")
        or (len(raw) >= 2 and raw[1] == ":")
    ):
        raise ValueError("file_name must be a workspace-relative path")
    parts = [part for part in raw.split("/") if part and part != "."]
    if not parts:
        raise ValueError("file_name is empty")
    if any(part == ".." for part in parts):
        raise ValueError("path traversal is not allowed")
    return "/".join(normalize_stored_file_name(part) for part in parts)


class FileInfoOp(object):
    """FileInfo 表操作：按内容/上传流写入，并维护 file_id 唯一索引。"""

    @classmethod
    @timer()
    async def add_by_content(
        cls,
        filename: str,
        content: str,
        file_id: str,
        description: str = None,
        request_id: str = None,
    ) -> FileInfo:
        """文本内容落盘 + 写元数据。"""
        # 先落盘再写元数据，确保数据库中的 file_path 指向已经存在的文件。
        filename = normalize_stored_file_name(filename)
        file_path = await FileDB.save(filename, content, scope=request_id)
        file_info = FileInfo(
            file_id=file_id,
            filename=filename,
            file_path=file_path,
            description=description,
            file_size=os.path.getsize(file_path),
            status=1,
            request_id=request_id,
        )
        return await cls.add(file_info)

    @staticmethod
    @timer()
    async def add_by_file(
        file: UploadFile,
        file_id: str,
        request_id: str = None,
        relative_path: str = None,
    ) -> FileInfo:
        """multipart 上传流落盘 + 写元数据。"""
        relative_name = normalize_stored_relative_path(
            relative_path or file.filename
        )
        # 传输文件名仍用 basename；逻辑身份和物理目录由 relative_name 保留。
        file.filename = Path(relative_name).name
        file_path = await FileDB.save_by_data(
            file, scope=request_id, relative_name=relative_name
        )

        file_info = FileInfo(
            file_id=file_id,
            filename=relative_name,
            file_path=file_path,
            description="",
            file_size=os.path.getsize(file_path),
            status=1,
            request_id=request_id,
        )
        return await FileInfoOp.add(file_info)

    @classmethod
    @timer()
    async def add_by_existing_path(
        cls,
        filename: str,
        local_path: str,
        file_id: str,
        description: str = None,
        request_id: str = None,
    ) -> FileInfo:
        """登记已落盘文件：不拷贝内容，file_path 指向已有绝对路径。"""
        # register 与 upload 的区别是只登记引用；因此必须先验证源文件存在，再把绝对路径写入元数据。
        filename = normalize_stored_relative_path(filename)
        if not local_path:
            raise FileNotFoundError("local file not found: empty path")
        abs_path = os.path.realpath(os.path.abspath(local_path))
        if not _is_path_under_allowed_root(abs_path):
            raise PermissionError(f"local file is outside allowed roots: {local_path}")
        if not os.path.isfile(abs_path):
            raise FileNotFoundError(f"local file not found: {local_path}")
        file_info = FileInfo(
            file_id=file_id,
            filename=filename,
            file_path=abs_path,
            description=description or "",
            file_size=os.path.getsize(abs_path),
            status=1,
            request_id=request_id,
        )
        return await cls.add(file_info)

    @staticmethod
    @timer()
    async def add(file_info: FileInfo) -> FileInfo:
        """插入或按 file_id 覆盖更新（同 ID 视为重新上传）。"""
        # file_id 是逻辑唯一键；重复上传更新同一元数据行，避免列表接口出现同一文件的多个活动版本。
        file_id = file_info.file_id
        f = await FileInfoOp.get_by_file_id(file_info.file_id)
        async with async_session_local() as session:
            if f:
                f.status = 1
                f.file_size = file_info.file_size
                f.file_path = file_info.file_path
                f.filename = file_info.filename
                f.description = file_info.description
                f.request_id = file_info.request_id
                session.add(f)
            else:
                session.add(file_info)
            await session.commit()
        return await FileInfoOp.get_by_file_id(file_id)

    @staticmethod
    @timer()
    async def get_by_file_id(file_id: str) -> FileInfo:
        """按逻辑 file_id 查单条。"""
        async with async_session_local() as session:
            state = select(FileInfo).where(FileInfo.file_id == file_id)
            result = await session.execute(state)
            return result.scalars().one_or_none()

    @staticmethod
    @timer()
    async def get_by_file_ids(file_ids: List[str]) -> List[FileInfo]:
        """批量按 file_id 查询。"""
        async with async_session_local() as session:
            state = select(FileInfo).where(FileInfo.file_id.in_(file_ids))
            result = await session.execute(state)
            return result.scalars().all()

    @staticmethod
    @timer()
    async def get_by_request_id(request_id: str) -> List[FileInfo]:
        """按会话/请求 ID 列出全部产物文件。"""
        async with async_session_local() as session:
            state = select(FileInfo).where(FileInfo.request_id == request_id)
            result = await session.execute(state)
            return result.scalars().all()


def get_file_preview_url(file_id: str, file_name: str):
    """拼预览 URL：/preview/{request_id}/{file_name}，file_name 可含相对目录。"""
    normalized_file_name = normalize_stored_relative_path(file_name)
    return f"{os.getenv('FILE_SERVER_URL')}/preview/{file_id}/{normalized_file_name}"


def get_file_download_url(file_id: str, file_name: str):
    """拼下载 URL：/download/{request_id}/{file_name}，file_name 可含相对目录。"""
    normalized_file_name = normalize_stored_relative_path(file_name)
    return f"{os.getenv('FILE_SERVER_URL')}/download/{file_id}/{normalized_file_name}"
