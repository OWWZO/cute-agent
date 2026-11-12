from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


def _canonical_text_sha256(path: Path) -> str:
    payload = path.read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(payload).hexdigest()


def _safe_extract(archive: zipfile.ZipFile, target: Path) -> None:
    root = target.resolve()
    for member in archive.infolist():
        destination = (target / member.filename).resolve()
        if destination != root and root not in destination.parents:
            raise ValueError(f"unsafe archive member: {member.filename}")
    archive.extractall(target)


def _download_source(revision: str, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    archive_path = target.parent / f"deepresearch-{revision}.zip"
    if not archive_path.exists():
        url = (
            "https://codeload.github.com/Ayanami0730/"
            f"deep_research_bench/zip/{revision}"
        )
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "cute-evals"},
        )
        with urllib.request.urlopen(request, timeout=300) as response:
            archive_path.write_bytes(response.read())
    staging = target.parent / f".source-{revision}"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(archive_path) as archive:
        _safe_extract(archive, staging)
    children = [item for item in staging.iterdir() if item.is_dir()]
    if len(children) != 1:
        raise ValueError(
            f"unexpected DeepResearch Bench archive layout: {staging}"
        )
    if target.exists():
        shutil.rmtree(target)
    children[0].replace(target)
    shutil.rmtree(staging)
    (target / ".source-complete").write_text(revision, encoding="utf-8")
    return target


def ensure_source(
    *,
    revision: str,
    data_home: Path,
    source_dir: Path | None = None,
    query_sha256: str,
) -> Path:
    if source_dir is not None:
        source = source_dir.resolve()
    else:
        env_source = os.environ.get("DEEP_RESEARCH_BENCH_SOURCE")
        if env_source:
            source = Path(env_source).resolve()
        else:
            source = (
                data_home
                / "deepresearch"
                / revision
                / "source"
            ).resolve()
            if not (source / ".source-complete").exists():
                source = _download_source(revision, source)
    query_file = source / "data" / "prompt_data" / "query.jsonl"
    if not query_file.exists():
        raise FileNotFoundError(f"DeepResearch Bench query file not found: {query_file}")
    actual = _canonical_text_sha256(query_file)
    if actual.lower() != query_sha256.lower():
        raise ValueError(
            "DeepResearch Bench query hash mismatch: "
            f"expected {query_sha256}, got {actual}"
        )
    return source


def _run_official(
    command: list[str],
    *,
    source: Path,
    env: dict[str, str],
    log_path: Path,
) -> None:
    completed = subprocess.run(
        command,
        cwd=source,
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    log_path.write_text(
        f"command: {json.dumps(command, ensure_ascii=False)}\n"
        f"exitCode: {completed.returncode}\n"
        f"stdout:\n{completed.stdout}\n"
        f"stderr:\n{completed.stderr}\n",
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"official evaluator command failed with exit {completed.returncode}; "
            f"see {log_path}"
        )


def _parse_metric_file(path: Path) -> dict[str, float]:
    metrics: dict[str, float] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        try:
            metrics[key.strip()] = float(raw.strip())
        except ValueError:
            continue
    return metrics


def _fact_coverage(path: Path) -> dict[str, float | int]:
    """Expose unknown facts so valid_rate cannot hide scrape failures."""
    counts = {"supported": 0, "unsupported": 0, "unknown": 0}
    if not path.exists():
        return {
            "totalFacts": 0,
            "supportedFacts": 0,
            "unsupportedFacts": 0,
            "unknownFacts": 0,
            "supportedShare": 0.0,
            "verifiedCoverage": 0.0,
            "unknownRate": 0.0,
        }
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        for citation in row.get("citations_deduped", {}).values():
            for item in citation.get("validate_res", []) or []:
                result = str(item.get("result") or "")
                if result in counts:
                    counts[result] += 1
    total = sum(counts.values())
    verified = counts["supported"] + counts["unsupported"]
    return {
        "totalFacts": total,
        "supportedFacts": counts["supported"],
        "unsupportedFacts": counts["unsupported"],
        "unknownFacts": counts["unknown"],
        "supportedShare": counts["supported"] / total if total else 0.0,
        "verifiedCoverage": verified / total if total else 0.0,
        "unknownRate": counts["unknown"] / total if total else 0.0,
    }


def _windows_module_wrapper(output_dir: Path) -> Path:
    path = output_dir / "_official_module_runner.py"
    if not path.exists():
        path.write_text(
            "import multiprocessing\n"
            "import os\n"
            "import runpy\n"
            "import sys\n"
            "import requests\n"
            "\n"
            "_original_set_start_method = multiprocessing.set_start_method\n"
            "_original_requests_get = requests.get\n"
            "\n"
            "def _set_start_method(method, *args, **kwargs):\n"
            "    if method == 'fork':\n"
            "        return None\n"
            "    return _original_set_start_method(method, *args, **kwargs)\n"
            "\n"
            "def _requests_get(*args, **kwargs):\n"
            "    kwargs.setdefault('timeout', (10, 90))\n"
            "    return _original_requests_get(*args, **kwargs)\n"
            "\n"
            "multiprocessing.set_start_method = _set_start_method\n"
            "requests.get = _requests_get\n"
            "sys.path.insert(0, os.getcwd())\n"
            "module = sys.argv[1]\n"
            "sys.argv = [module, *sys.argv[2:]]\n"
            "runpy.run_module(module, run_name='__main__', alter_sys=True)\n",
            encoding="utf-8",
        )
    return path


def _official_module_command(
    module: str,
    *arguments: str,
    output_dir: Path,
) -> list[str]:
    if os.name == "nt":
        return [
            sys.executable,
            "-u",
            str(_windows_module_wrapper(output_dir)),
            module,
            *arguments,
        ]
    return [sys.executable, "-u", "-m", module, *arguments]


def evaluate_official(
    *,
    source_dir: Path,
    data_home: Path,
    revision: str,
    query_sha256: str,
    raw_data_path: Path,
    target_model: str,
    output_dir: Path,
    race_model: str,
    clean_model: str,
    fact_model: str,
    base_url: str | None,
    api_key: str | None,
    jina_api_key: str | None,
    max_workers: int = 10,
    limit: int | None = None,
) -> dict[str, Any]:
    if not api_key:
        return {
            "status": "unverified",
            "reason": "official evaluator requires an OpenAI-compatible API key",
        }
    if not jina_api_key:
        return {
            "status": "unverified",
            "reason": "official FACT evaluator requires JINA_API_KEY",
        }

    source = ensure_source(
        revision=revision,
        data_home=data_home,
        source_dir=source_dir,
        query_sha256=query_sha256,
    )
    query_file = source / "data" / "prompt_data" / "query.jsonl"
    output_dir.mkdir(parents=True, exist_ok=True)
    race_dir = output_dir / "race"
    fact_dir = output_dir / "fact"
    cleaned_dir = output_dir / "cleaned"
    race_dir.mkdir(parents=True, exist_ok=True)
    fact_dir.mkdir(parents=True, exist_ok=True)
    cleaned_dir.mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()
    env.update(
        {
            "LLM_BACKEND": "openai",
            "OPENAI_API_KEY": api_key,
            "JINA_API_KEY": jina_api_key,
            "RACE_MODEL": race_model,
            "CLEAN_MODEL": clean_model,
            "FACT_MODEL": fact_model,
        }
    )
    if base_url:
        env["OPENAI_BASE_URL"] = base_url

    raw_dir = raw_data_path.parent
    official_raw = raw_dir / f"{target_model}.jsonl"
    if raw_data_path.resolve() != official_raw.resolve():
        shutil.copy2(raw_data_path, official_raw)

    race_command = [
        sys.executable,
        "-u",
        "deepresearch_bench_race.py",
        target_model,
        "--raw_data_dir",
        str(raw_dir),
        "--cleaned_data_dir",
        str(cleaned_dir),
        "--max_workers",
        str(max_workers),
        "--query_file",
        str(query_file),
        "--output_dir",
        str(race_dir),
        "--force",
    ]
    if limit is not None:
        race_command.extend(["--limit", str(limit)])
    try:
        _run_official(
            race_command,
            source=source,
            env=env,
            log_path=output_dir / "race.log",
        )
        race_metrics = _parse_metric_file(race_dir / "race_result.txt")
    except Exception as exc:  # noqa: BLE001 - evaluator boundary is unverified
        return {
            "status": "unverified",
            "stage": "race",
            "reason": str(exc),
            "sourceRevision": revision,
        }

    fact_files = {
        "extracted": fact_dir / "extracted.jsonl",
        "deduplicated": fact_dir / "deduplicated.jsonl",
        "scraped": fact_dir / "scraped.jsonl",
        "validated": fact_dir / "validated.jsonl",
    }
    fact_workers = 1 if os.name == "nt" else max_workers
    fact_commands = [
        (
            "extract",
            _official_module_command(
                "utils.extract",
                "--raw_data_path",
                str(official_raw),
                "--output_path",
                str(fact_files["extracted"]),
                "--query_data_path",
                str(query_file),
                "--n_total_process",
                str(fact_workers),
                output_dir=output_dir,
            ),
        ),
        (
            "deduplicate",
            _official_module_command(
                "utils.deduplicate",
                "--raw_data_path",
                str(fact_files["extracted"]),
                "--output_path",
                str(fact_files["deduplicated"]),
                "--query_data_path",
                str(query_file),
                "--n_total_process",
                str(fact_workers),
                output_dir=output_dir,
            ),
        ),
        (
            "scrape",
            _official_module_command(
                "utils.scrape",
                "--raw_data_path",
                str(fact_files["deduplicated"]),
                "--output_path",
                str(fact_files["scraped"]),
                "--n_total_process",
                str(fact_workers),
                output_dir=output_dir,
            ),
        ),
        (
            "validate",
            _official_module_command(
                "utils.validate",
                "--raw_data_path",
                str(fact_files["scraped"]),
                "--output_path",
                str(fact_files["validated"]),
                "--query_data_path",
                str(query_file),
                "--n_total_process",
                str(fact_workers),
                output_dir=output_dir,
            ),
        ),
    ]
    try:
        for name, command in fact_commands:
            _run_official(
                command,
                source=source,
                env=env,
                log_path=output_dir / f"fact-{name}.log",
            )
        fact_result = fact_dir / "fact_result.txt"
        _run_official(
            _official_module_command(
                "utils.stat",
                "--input_path",
                str(fact_files["validated"]),
                "--output_path",
                str(fact_result),
                output_dir=output_dir,
            ),
            source=source,
            env=env,
            log_path=output_dir / "fact-stat.log",
        )
        fact_metrics = _parse_metric_file(fact_result)
    except Exception as exc:  # noqa: BLE001 - evaluator boundary is unverified
        return {
            "status": "unverified",
            "stage": "fact",
            "reason": str(exc),
            "sourceRevision": revision,
            "race": race_metrics,
        }

    return {
        "status": "complete",
        "sourceRevision": revision,
        "targetModel": target_model,
        "judge": {
            "raceModel": race_model,
            "cleanModel": clean_model,
            "factModel": fact_model,
            "baseUrl": base_url,
        },
        "race": race_metrics,
        "fact": fact_metrics,
        "factCoverage": _fact_coverage(fact_files["validated"]),
        "artifacts": {
            "race": str(race_dir / "race_result.txt"),
            "fact": str(fact_dir / "fact_result.txt"),
        },
    }
