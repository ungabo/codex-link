param(
    [int]$Port = 18765,
    [string]$BindHost = "0.0.0.0"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $PSCommandPath
$bridgeScript = Join-Path $scriptDir "codex_link_bridge.py"
$logDir = Join-Path $scriptDir "logs"
$outLog = Join-Path $logDir "bridge.out.txt"
$errLog = Join-Path $logDir "bridge.err.txt"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Get-LanUrls {
    param([int]$ServicePort)

    Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.PrefixOrigin -ne "WellKnown"
        } |
        Sort-Object InterfaceMetric, InterfaceAlias |
        ForEach-Object { "http://$($_.IPAddress):$ServicePort/link" }
}

function Show-EndpointInfo {
    param([int]$ServicePort)

    Write-Host ""
    Write-Host "Phone endpoint(s):" -ForegroundColor Cyan
    $urls = @(Get-LanUrls -ServicePort $ServicePort)
    if ($urls.Count -eq 0) {
        Write-Host "  Could not detect a LAN IPv4 address. Check Wi-Fi/Ethernet connection." -ForegroundColor Yellow
    } else {
        foreach ($url in $urls) {
            Write-Host "  $url"
        }
    }
    Write-Host ""
    Write-Host "Keep this window open while using the phone app." -ForegroundColor Yellow
    Write-Host "Press Ctrl+C in this window to stop the bridge." -ForegroundColor Yellow
    Write-Host ""
}

if (-not (Test-Path -LiteralPath $bridgeScript)) {
    throw "Bridge script not found: $bridgeScript"
}

$existing = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq $Port } |
    Select-Object -First 1

if ($existing) {
    Write-Host "Codex Link bridge already appears to be listening on port $Port." -ForegroundColor Green
    Write-Host "Owning process id: $($existing.OwningProcess)"
    Show-EndpointInfo -ServicePort $Port
    return
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) {
    throw "Python was not found on PATH. Install Python or add it to PATH, then run this shortcut again."
}
$pythonExe = $pythonCommand.Source

Write-Host "Starting Codex Link bridge..." -ForegroundColor Green
Write-Host "Bridge script: $bridgeScript"
Write-Host "Logs:"
Write-Host "  $outLog"
Write-Host "  $errLog"
Show-EndpointInfo -ServicePort $Port

& $pythonExe $bridgeScript --host $BindHost --port $Port 1>> $outLog 2>> $errLog
