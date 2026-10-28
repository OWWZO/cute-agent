#Requires -Version 7.0
<#
.SYNOPSIS
    启动前端开发服务器（:3000）。

.DESCRIPTION
    没有构建步骤：vite dev 按需编译源码并热更新浏览器，
    重启进程就足以让源码改动生效（生产产物才需要 pnpm build）。
#>

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
Set-Location (Join-Path $Root 'ui')

$Port = 3000

function Write-Step([string]$Text) { Write-Host $Text -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "      $Text" -ForegroundColor DarkGray }

Write-Step "[1/2] 停止占用端口 $Port 的进程 ..."
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    Write-Ok "发现 PID $($listener.OwningProcess)，正在结束 ..."
    Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { break }
    }
} else {
    Write-Ok '端口空闲，继续。'
}

if (-not (Test-Path 'node_modules')) {
    Write-Ok '未找到 node_modules，正在安装依赖 ...'
    & pnpm install
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Step "[2/2] 启动前端 http://localhost:$Port ..."
& pnpm dev
