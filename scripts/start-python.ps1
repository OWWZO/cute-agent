#Requires -Version 7.0
<#
.SYNOPSIS
    启动 Python 工具运行时（:1601 api / :1602 sandbox）。

.DESCRIPTION
    包装已有的 cute-tool\start-split.ps1，而不是修改它：
    那个脚本在端口被占用时会直接报错退出，所以这里先释放端口以获得「重启」语义。

    没有构建步骤：server.py 直接跑源码。依赖有变动时先手动执行 uv sync。
#>

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
Set-Location $Root

$Ports = @(1601, 1602)

function Write-Step([string]$Text) { Write-Host $Text -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "      $Text" -ForegroundColor DarkGray }

Write-Step "[1/2] 停止占用端口 $($Ports -join ' / ') 的进程 ..."
$found = $false
foreach ($p in $Ports) {
    $listener = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        Write-Ok "端口 $p 被 PID $($listener.OwningProcess) 占用，正在结束 ..."
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
        $found = $true
    }
}
if (-not $found) { Write-Ok '端口空闲，继续。' }

# 等待端口真正释放：被结束的进程可能短暂留在 TIME_WAIT，
# 而 start-split.ps1 遇到占用端口会直接抛错。
for ($i = 0; $i -lt 20; $i++) {
    $busy = $Ports | Where-Object {
        Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue
    }
    if (-not $busy) { break }
    Start-Sleep -Milliseconds 500
}

Write-Step '[2/2] 启动 cute-tool ...'
& pwsh -NoProfile -File (Join-Path $Root 'cute-tool\start-split.ps1')
