param(
    [int]$LocalPort = 18765
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $PSCommandPath
$bridgeScript = Join-Path $scriptDir "codex_link_bridge.py"
$tunnelScript = Join-Path $scriptDir "codex_link_remote_tunnel.py"
$configPath = Join-Path $scriptDir "remote_tunnel_config.json"
$logDir = Join-Path $scriptDir "logs"
$bridgeOutLog = Join-Path $logDir "bridge.remote.out.txt"
$bridgeErrLog = Join-Path $logDir "bridge.remote.err.txt"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Remote tunnel config not found: $configPath. Run remote_relay\deploy_codex_link_relay.py first."
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) {
    throw "Python was not found on PATH."
}
$pythonExe = $pythonCommand.Source

$existing = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq $LocalPort } |
    Select-Object -First 1

if (-not $existing) {
    Write-Host "Starting local Codex Link bridge on 127.0.0.1:$LocalPort..." -ForegroundColor Green
    $bridgeArgs = "`"$bridgeScript`" --host 127.0.0.1 --port $LocalPort"
    Start-Process `
        -FilePath $pythonExe `
        -ArgumentList $bridgeArgs `
        -WindowStyle Hidden `
        -RedirectStandardOutput $bridgeOutLog `
        -RedirectStandardError $bridgeErrLog
    Start-Sleep -Seconds 2
} else {
    Write-Host "Local Codex Link bridge already listening on port $LocalPort." -ForegroundColor Green
}

Write-Host "Starting Codex Link web tunnel. Keep this window open." -ForegroundColor Cyan
Write-Host "Remote endpoint: https://www.sitesindevelopment.com/codex-link/index.php/link"
& $pythonExe $tunnelScript
