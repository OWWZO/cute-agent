# Cute Agent UI 重构  剩余任务交接书

> 交接时间：2026-10-24 22:50
> 项目路径：`D:\Java Code\ai-agent\Cute-agent\ui`（Vite + React + TS + Tailwind v4）
>  注意：项目目录统一为 `Cute-agent`，旧路径已失效。
> 前置背景：已完成整体代码评审（主页 / Agent 执行界面 / 工作区），用户确认按 6 项优先级修复，执行到第 2 项中途因终端环境变化中断。

##  接手前必读  环境状态

1. **终端**：当前可用终端 terminal_id `379c695e-5dc2-4b4a-a5d0-640d4c989c77`，connect_config_id `local`（Windows PowerShell）。
2. **工具坑位记录**：
   - `remote_multi_edit` / `remote_edit` / `remote_write` 在该 Windows 本地环境报 `rename: cross-device link` 错误，**不可用**；文件修改请用 PowerShell `[IO.File]::ReadAllText` + `.Re
place()` + `[IO.File]::WriteAllText`（带锚点存在性校验）。
   - 终端回显会把长命令拆行显示导致输出难读，验证用短命令加正则 Matches 统计标记。
   - 长命令可能报超时但实际已执行成功，**先验证再决定是否重试，严禁原样重跑写入类命令**（有重复插入风险）。

## 一、已完成变更（已验证）

### 1. 样式 token 收敛   完成

**文件 `src/global.css`：**
- light 主题（`--chat-interactive-active: #eeeeef;` 之后）新增：
```css
/* Category tab active state (warm accent pair) */
--chat-tab-active-bg: oklch(0.94 0.03 90);
--chat-tab-active-border: oklch(0.83 0.03 90);
```
- dark 主题（`--chat-interactive-active: oklch(0.27 0.016 75);` 之后）新增：
```css
/* Category tab active state: dark uses surface level + warm border */
--chat-tab-active-bg: oklch(0.25 0.016 75);
--chat-tab-active-border: oklch(0.35 0.02 80);
```

**文件 `src/pages/Home/WelcomeView.tsx`：**
- 分类 Tab 激活态：`border-[oklch(0.83_0.03_90)] bg-[oklch(0.94_0.03_90)]`  `border-[var(--chat-tab-active-border)] bg-[var(--chat-tab-active-bg)]`
- 验证结果：文件内 oklch 残留 0，token 引用 2 处。
- 注：`SUGGESTION_ICON_CLASS` 中的品牌色 hex（#ff0000 等）为有意保留的品牌例外区，不在收敛范围。

### 2. FileList 搜索与类型筛选   进行中，文件当前处于未完成的 JSX 状态

**文件 `src/components/ActionView/FileList.tsx`（原 346 行）已完成的写入：**

a) 导入区：新增 `import { cn } from "@/lib/utils";`（首行前）和 lucide-react 导入中的 `Search`（`Presentation,` 之后）。

b) `getFileIcon` 函数与 `const FileList` 之间插入了类型筛选 helper（ 验证 helpers=2）：
- `type FileTypeFilter = "all" | "document" | "spreadsheet" | "presentation" | "code" | "image" | "other"`
- `FILE_TYPE_FILTERS` 数组（全部/文档/表格/演示/代码/图片/其他）
- `FILE_TYPE_GROUPS` 扩展名映射（document: doc/docx/pdf/md/markdown/txt；spreadsheet: csv/xlsx/xls；presentation: ppt/pptx/pps/ppsx；code: html/htm/code/js/jsx/ts/tsx/py/java/css/scss/less/json/xml/yml/yaml/sh/bash/sql/go/rs/c/cpp/h/hpp/log；image: png/jpg/jpe
g/gif/webp/svg/bmp）
- `getFileTypeGroup(type?: string): FileTypeFilter`

c) 组件内新增状态（ 验证 states=4）：
```tsx
const [searchText, setSearchText] = useState("");
const [typeFilter, setTypeFilter] = useState<FileTypeFilter>("all");
```

d) `fileItem` 派生之前插入了 `visibleFiles` useMemo（ 验证 visible=1）：按 typeFilter 过滤 + searchText 对 name/relativePath 的小写包含匹配。

e) 列表视图头部替换（输出 OK head，但最后一次有效验证结果矛盾：hasActiveFilters=1、placeholder_search=1、buildWorkspaceTree(visibleFiles)=0，需重新确认实际状态）：将原来的
```tsx
return (
  <div className="h-full overflow-auto p-2">
    <WorkspaceFileTree
      nodes={buildWorkspaceTree(fileList)}
```
替换为：`hasActiveFilters` 常量 + `flex h-full flex-col` 容器 + 搜索输入框（Search 图标 + placeholder『搜索文件名或路径』）+ 类型筛选 chips（FILE_TYPE_FILTERS.map，选中态 border-input-border-focused）+ `min-h-0 flex-1 overflow-auto p-2` 滚动区 + `{visibleFiles.length ? (<WorkspaceFileTree nodes={buildWorkspaceTree(visibleFiles)}`

** 未完成的收尾（接手后第一优先级）：**
- 上述替换只写到 `nodes={buildWorkspaceTree(visibleFiles)}`，条件分支没有闭合。需要把原有的
```tsx
          selectedFileKey={activeItem}
          onSelectFile={(file) => setActiveItem(workspaceFileKey(file))}
        />
      </div>
    );
```
改为（示意）：
```tsx
              selectedFileKey={activeItem}
              onSelectFile={(file) => setActiveItem(workspaceFileKey(file))}
            />
          ) : (
            <div className="px-2 py-8 text-center text-ui-sm text-foreground-subtle">
              没有匹配的文件
            </div>
          )}
        </div>
      </div>
    );
```
- 样式约束：颜色一律走语义 token（text-foreground-subtle / bg-surface-hover / border-input-border-focused 等），禁 hex / Tailwind 调色板（ZCode 迁移规范）。
- 完成后运行 `npx tsc --noEmit`（或项目 lint）验证语法。
