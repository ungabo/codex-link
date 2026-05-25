param(
    [string]$Serial = "",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = "codex-link-debug.apk",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Package = "com.codexlink.android"
$Activity = "com.codexlink.android/.MainActivity"
$OutDir = $PSScriptRoot

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    if ($Serial) {
        & $Adb -s $Serial @Args
    } else {
        & $Adb @Args
    }
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Args -join ' ')"
    }
}

function Dump-Ui {
    param([string]$Name)
    $remote = "/sdcard/codex-link-$Name.xml"
    Invoke-Adb shell uiautomator dump $remote | Out-Null
    Invoke-Adb pull $remote (Join-Path $OutDir "$Name.xml") | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    return Get-Content -Raw -LiteralPath (Join-Path $OutDir "$Name.xml")
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Step)
    if (-not $Text.Contains($Needle)) {
        throw "Smoke test failed at $Step. Missing UI text: $Needle"
    }
    Write-Host "OK: $Step -> $Needle"
}

if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb not found: $Adb"
}

Push-Location $Root
try {
    Invoke-Adb devices | Out-Host
    if (-not $SkipInstall) {
        Invoke-Adb install -r $Apk | Out-Host
    }

    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell am start -n $Activity | Out-Null
    Start-Sleep -Seconds 2

    $homeUi = Dump-Ui "smoke-home"
    Assert-Contains $homeUi "Codex Link" "launch"
    Assert-Contains $homeUi "Chats" "home chats"
    Assert-Contains $homeUi "Refresh" "home refresh"
    Assert-Contains $homeUi "Status" "status button"
    Assert-Contains $homeUi "Queue" "queue overview button"

    Invoke-Adb shell input tap 300 450 | Out-Null
    Start-Sleep -Seconds 5
    $catalog = Dump-Ui "smoke-catalog"
    Assert-Contains $catalog "chats loaded" "catalog refresh"

    Invoke-Adb shell input tap 500 1260 | Out-Null
    Start-Sleep -Seconds 4
    $thread = Dump-Ui "smoke-thread"
    Assert-Contains $thread "Chats" "thread back button"
    Assert-Contains $thread "Files" "thread files button"
    Assert-Contains $thread "Image" "thread image attach"

    Invoke-Adb shell input tap 940 210 | Out-Null
    Start-Sleep -Seconds 1
    $actions = Dump-Ui "smoke-actions"
    Assert-Contains $actions "Older" "older control"
    Assert-Contains $actions "Full" "full control"
    Assert-Contains $actions "Search in chat" "thread search"
    Assert-Contains $actions "Checkpoint" "checkpoint control"
    Assert-Contains $actions "Revert" "revert control"

    Invoke-Adb shell input tap 520 350 | Out-Null
    Start-Sleep -Seconds 1
    $keyboard = Dump-Ui "smoke-keyboard"
    if ($keyboard.Contains("Older") -or $keyboard.Contains("Search in chat")) {
        throw "Smoke test failed: Actions did not collapse when composer focused."
    }
    Assert-Contains $keyboard "Image" "keyboard composer image button"
    Assert-Contains $keyboard "Queue" "keyboard composer send/queue button"

    Write-Host "Codex Link Android smoke test passed."
} finally {
    Pop-Location
}
