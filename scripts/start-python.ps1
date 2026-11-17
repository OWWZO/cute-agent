#Requires -Version 7.0
param(
    [string[]]$WorkspaceRoots = @()
)
<#
.SYNOPSIS
    启动 Python 工具运行时（:1601 api / :1602 sandbox）。

.DESCRIPTION
    包装 cute-tool\start-split.ps1：启动前清理端口及其残留子进程，
    脚本退出时再清理一次，避免多 worker 的子进程成为孤儿。

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

function Get-ProcessTreeIds([int]$RootId) {
    $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $processIds = [System.Collections.Generic.HashSet[int]]::new()
    [void]$processIds.Add($RootId)

    do {
        $added = $false
        foreach ($process in $allProcesses) {
            if ($processIds.Contains([int]$process.ParentProcessId) -and
                $processIds.Add([int]$process.ProcessId)) {
                $added = $true
            }
        }
    } while ($added)

    return @($processIds)
}

function Stop-PortProcessTree([int]$Port) {
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) {
        return $false
    }

    $processIds = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($listener in $listeners) {
        foreach ($processId in Get-ProcessTreeIds ([int]$listener.OwningProcess)) {
            [void]$processIds.Add([int]$processId)
        }
    }

    foreach ($processId in $processIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
    return $true
}

function Assert-PortsFree([int[]]$PortsToCheck) {
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $busy = @(
            foreach ($port in $PortsToCheck) {
                Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            }
        )
        if ($busy.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)

    $details = $busy |
        Select-Object LocalPort, OwningProcess |
        Format-Table -AutoSize |
        Out-String
    throw "Ports did not release after cleanup:`n$details"
}

Write-Step "[1/2] 停止占用端口 $($Ports -join ' / ') 的进程 ..."
$found = $false
foreach ($p in $Ports) {
    $listeners = @(Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $owners = $listeners.OwningProcess | Sort-Object -Unique
        Write-Ok "端口 $p 被 PID $($owners -join ', ') 占用，正在结束进程树 ..."
        [void](Stop-PortProcessTree $p)
        $found = $true
    }
}
if (-not $found) { Write-Ok '端口空闲，继续。' }

Assert-PortsFree $Ports

Write-Step '[2/2] 启动 cute-tool ...'
if ($WorkspaceRoots.Count -gt 0) {
    $resolvedWorkspaceRoots = @(
        foreach ($workspaceRoot in $WorkspaceRoots) {
            if (-not [string]::IsNullOrWhiteSpace($workspaceRoot)) {
                [IO.Path]::GetFullPath($workspaceRoot)
            }
        }
    )
    if ($resolvedWorkspaceRoots.Count -eq 0) {
        throw "WorkspaceRoots must contain at least one non-empty path."
    }
    $env:CODE_EXECUTION_WORKSPACE_ROOTS = (
        $resolvedWorkspaceRoots -join [IO.Path]::PathSeparator
    )
    Write-Ok "允许代码执行的工作区: $($env:CODE_EXECUTION_WORKSPACE_ROOTS)"
}
try {
    & pwsh -NoProfile -File (Join-Path $Root 'cute-tool\start-split.ps1')
}
finally {
    $cleaned = $false
    foreach ($port in $Ports) {
        if (Stop-PortProcessTree $port) {
            $cleaned = $true
        }
    }
    if ($cleaned) {
        Write-Ok '已清理 cute-tool 残留进程。'
    }
}
