#Requires -Version 7.0
<#
.SYNOPSIS
    启动任务复盘 Python 评测后端（:8765）。

.DESCRIPTION
    启动 cute_evals.service.app，并把非敏感的 task-review 配置指向仓库内
    evals\agent\config\task-review.yaml。

    服务启动前和脚本退出时都会清理 8765 端口，避免 uvicorn 残留进程。
#>

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
$EvalRoot = Join-Path $Root 'evals\agent'
$Python = Join-Path $EvalRoot '.venv\Scripts\python.exe'
$Config = Join-Path $EvalRoot 'config\task-review.yaml'
$Port = 8765

function Write-Step([string]$Text) { Write-Host $Text -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "      $Text" -ForegroundColor DarkGray }

function Stop-PortProcess([int]$PortToStop) {
    $listener = Get-NetTCPConnection -LocalPort $PortToStop -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) {
        return $false
    }
    Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 250
        if (-not (Get-NetTCPConnection -LocalPort $PortToStop -State Listen -ErrorAction SilentlyContinue)) {
            return $true
        }
    }
    return $true
}

if (-not (Test-Path $Python)) {
    throw "未找到评测 Python 虚拟环境：$Python。请先在 evals\agent 执行 uv sync --extra service --extra judge。"
}

Write-Step "[1/2] 停止占用端口 $Port 的进程 ..."
if (Stop-PortProcess $Port) {
    Write-Ok '已停止旧评测服务。'
} else {
    Write-Ok '端口空闲，继续。'
}

$env:CUTE_EVAL_JAVA_BASE_URL = 'http://127.0.0.1:8100'
$env:CUTE_EVAL_TASK_REVIEW_CONFIG = $Config
$env:PYTHONPATH = 'src'

Set-Location $EvalRoot
Write-Step "[2/2] 启动评测后端 http://127.0.0.1:$Port ..."
try {
    & $Python -m uvicorn cute_evals.service.app:app --host 127.0.0.1 --port $Port
}
finally {
    if (Stop-PortProcess $Port) {
        Write-Ok '已清理评测后端残留进程。'
    }
}
