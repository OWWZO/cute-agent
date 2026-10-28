#Requires -Version 7.0
<#
.SYNOPSIS
    启动 Java 后端（:8100），行为对齐 VS Code 调试器（Ctrl+F5）。

.DESCRIPTION
    流程：
      1. 结束占用 8100 端口的旧进程
      2. 增量编译（mvn -am，依赖模块的改动也会生效）
      3. 用「模块 target/classes + 依赖 jar」启动，不打包 jar

    依赖 classpath 缓存在 scripts/.classpath.txt，只在文件缺失时重新生成。
    改动 pom.xml 后删除该文件即可强制重建。
#>

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
Set-Location $Root

$Port = 8100
$CpCache = Join-Path $ScriptDir '.classpath.txt'
$MainClass = 'org.wwz.ai.Application'
$Modules = @(
    'Cute-agent-app', 'Cute-agent-trigger', 'Cute-agent-case', 'Cute-agent-domain',
    'Cute-agent-infrastructure', 'Cute-agent-api', 'Cute-agent-types'
)

function Write-Step([string]$Text) { Write-Host $Text -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "      $Text" -ForegroundColor DarkGray }
function Write-Fail([string]$Text) { Write-Host $Text -ForegroundColor Red }

# ---------- 1. 停止旧进程 ----------
Write-Step "[1/3] 停止占用端口 $Port 的进程 ..."
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    Write-Ok "发现 PID $($listener.OwningProcess)，正在结束 ..."
    Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    # 等待端口真正释放，避免新进程绑定失败。
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { break }
    }
} else {
    Write-Ok '端口空闲，继续。'
}

# ---------- 2. 增量编译 ----------
Write-Step '[2/3] 编译改动过的源码 ...'
& mvn -q -o -pl Cute-agent-app -am compile '-DskipTests'
if ($LASTEXITCODE -ne 0) {
    Write-Fail '[错误] 编译失败，请先修复上面的报错。'
    exit 1
}
Write-Ok '编译完成。'

# ---------- 3. 组装 classpath ----------
if (-not (Test-Path $CpCache)) {
    Write-Ok '首次运行，解析依赖 classpath（约 20 秒）...'
    # test scope：snakeyaml 通过它引入，runtime scope 会让 Spring 读不了 application.yml。
    # 输出必须是绝对路径，相对路径会落到模块目录下。
    & mvn -q -o -pl Cute-agent-app dependency:build-classpath `
        "-Dmdep.outputFile=$CpCache" '-DincludeScope=test'
    if ($LASTEXITCODE -ne 0) {
        Write-Fail '[错误] 解析 Maven classpath 失败。'
        exit 1
    }
}

$deps = (Get-Content -Raw $CpCache).Trim()
$moduleClasses = ($Modules |
    ForEach-Object { Join-Path $_ 'target\classes' } |
    Where-Object { Test-Path $_ }) -join ';'

Write-Step "[3/3] 启动后端 http://127.0.0.1:$Port ..."
# 工作目录保持在仓库根：SkillDirectoryResolver 从 user.dir 向上找 runtime\skills。
#
# Windows 的 CreateProcess 限制命令行约 32767 字符，而完整 classpath 有 35k+，
# 直接传 -cp 会报「文件名或扩展名太长」。改用 Java 9+ 的 @argfile。
# 注意：argfile 里被引号包住的值会把反斜杠当转义符，所以统一换成正斜杠。
$ArgsFile = Join-Path $ScriptDir '.java-args.txt'
$cp = ("$moduleClasses;$deps").Replace('\', '/')
@("-cp `"$cp`"", $MainClass) | Set-Content -Path $ArgsFile -Encoding ASCII

& java "@$ArgsFile"
