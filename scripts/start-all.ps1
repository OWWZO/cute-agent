#Requires -Version 7.0
<#
.SYNOPSIS
    一键启动完整开发环境：Python 工具运行时 + Java 后端 + 评测后端 + 前端。

.DESCRIPTION
    每个服务在独立的终端窗口中运行，日志实时滚动、按窗口标题区分：
      cute-python / cute-backend / cute-eval / cute-frontend

    关闭某个窗口即停止对应服务（其余不受影响）。
    每个子脚本会先停掉自己的端口，所以重复执行等价于「重启」。

    不用 Start-Job：作业内的 Write-Host 不会实时转发到控制台，
    看不到日志，等于白跑。
#>

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$services = @(
    @{ Title = 'cute-python';   Script = 'start-python.ps1' }
    @{ Title = 'cute-backend';  Script = 'start-backend.ps1' }
    @{ Title = 'cute-eval';     Script = 'start-eval.ps1' }
    @{ Title = 'cute-frontend'; Script = 'start-frontend.ps1' }
)

Write-Host '启动开发环境（每个服务一个窗口，关窗口即停止）' -ForegroundColor White

foreach ($s in $services) {
    $path = Join-Path $ScriptDir $s.Script
    # -NoExit 让窗口在脚本结束后停留：正常退出可看完整日志，报错可看堆栈。
    # 参数必须拼成单个字符串并手动加引号：-ArgumentList 传数组时 PowerShell
    # 只用空格连接，不会为含空格的路径（如 D:\Java Code\...）加引号，
    # 路径会被拆断导致脚本根本没跑起来。
    Start-Process -FilePath 'pwsh' `
        -ArgumentList "-NoProfile -NoExit -File `"$path`"" `
        -WindowStyle Normal
    Write-Host ("  已启动 {0}  {1}" -f $s.Title, $s.Script) -ForegroundColor DarkGray
    # Java 编译需要十几秒，让后端先走起来，前端最后起（要连 :8100）。
    Start-Sleep -Seconds 2
}

Write-Host ''
Write-Host '  backend  http://127.0.0.1:8100' -ForegroundColor DarkGray
Write-Host '  eval     http://127.0.0.1:8765' -ForegroundColor DarkGray
Write-Host '  frontend http://localhost:3000' -ForegroundColor DarkGray
Write-Host '  python   :1601 (api) / :1602 (sandbox)' -ForegroundColor DarkGray
