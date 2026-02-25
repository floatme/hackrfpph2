param(
    [switch]$RequireConnected = $false,
    [string]$PackageName = "com.orbit.app.free",
    [string]$ActivityName = "com.orbit.app.portapack.PortaPackRemoteActivity",
    [string]$AdbPath = "",
    [string]$DeviceSerial = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    if ($AdbPath -and (Test-Path $AdbPath)) {
        return $AdbPath
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    $fallbacks = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($p in $fallbacks) {
        if (Test-Path $p) {
            return $p
        }
    }
    throw "adb not found on PATH and no fallback path exists."
}

function Invoke-Adb {
    param([string]$Args)
    & $script:AdbPath $Args
}

function Get-OnlineDeviceSerials {
    $outLines = & $script:AdbPath devices
    $serials = @()
    foreach ($line in $outLines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^[^\s]+\s+device$") {
            $serials += (($trimmed -split "\s+")[0])
        }
    }
    return @($serials)
}

function Assert-DeviceConnected {
    $serials = @(Get-OnlineDeviceSerials)
    if (-not $serials -or $serials.Count -eq 0) {
        throw "No Android device connected through adb."
    }
}

function Dump-UiText {
    param([string]$LocalPath)
    & $script:AdbPath -s $script:DeviceSerial shell uiautomator dump /sdcard/hrfpp2_ui.xml | Out-Null
    & $script:AdbPath -s $script:DeviceSerial pull /sdcard/hrfpp2_ui.xml $LocalPath | Out-Null
    return Get-Content -Path $LocalPath -Raw
}

function Get-BoundsForText {
    param(
        [string]$UiXml,
        [string]$Text
    )
    $escaped = [Regex]::Escape($Text)
    $pattern = "text='$escaped'[^>]*bounds='\[(\d+),(\d+)\]\[(\d+),(\d+)\]'"
    $m = [regex]::Match($UiXml, $pattern)
    if (-not $m.Success) {
        $pattern2 = "text=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
        $m = [regex]::Match($UiXml, $pattern2)
    }
    if (-not $m.Success) {
        return $null
    }
    return @{
        x1 = [int]$m.Groups[1].Value
        y1 = [int]$m.Groups[2].Value
        x2 = [int]$m.Groups[3].Value
        y2 = [int]$m.Groups[4].Value
    }
}

function Tap-ByText {
    param(
        [string]$UiXml,
        [string]$Text
    )
    $b = Get-BoundsForText -UiXml $UiXml -Text $Text
    if ($null -eq $b) {
        return $false
    }
    $x = [int](($b.x1 + $b.x2) / 2)
    $y = [int](($b.y1 + $b.y2) / 2)
    & $script:AdbPath -s $script:DeviceSerial shell input tap $x $y | Out-Null
    Start-Sleep -Milliseconds 450
    return $true
}

function Get-LastControlValue {
    param([string]$UiXml)
    $m = [regex]::Match($UiXml, "Last control: ([^<`"]+)")
    if ($m.Success) {
        return $m.Groups[1].Value.Trim()
    }
    return $null
}

$script:AdbPath = Resolve-AdbPath
Assert-DeviceConnected
$serials = @(Get-OnlineDeviceSerials)
if (-not $DeviceSerial) {
    $script:DeviceSerial = $serials[0]
} else {
    if ($serials -notcontains $DeviceSerial) {
        throw "Requested DeviceSerial '$DeviceSerial' is not online. Online: $($serials -join ', ')"
    }
    $script:DeviceSerial = $DeviceSerial
}

Write-Host "Using adb: $script:AdbPath"
Write-Host "Using device: $script:DeviceSerial"
Write-Host "Launching HackRFPPH2 controller activity..."
& $script:AdbPath -s $script:DeviceSerial logcat -c | Out-Null
& $script:AdbPath -s $script:DeviceSerial shell am start -W -n "$PackageName/$ActivityName" | Out-Null
Start-Sleep -Milliseconds 700

$uiPath = Join-Path $PSScriptRoot "qa_ui.xml"
$ui = Dump-UiText -LocalPath $uiPath

if ($ui -notmatch "HackRFPPH2|HRFPP2 Controller|PortaPack Remote") {
    throw "App title was not found in UI dump (expected HackRFPPH2)."
}
if ($ui -notmatch "Not connected|Connected") {
    throw "Connection status text was not found."
}

# Scroll to controls section and ensure virtual controls are present.
$foundControls = $false
for ($i = 0; $i -lt 5; $i++) {
    & $script:AdbPath -s $script:DeviceSerial shell input swipe 720 2600 720 900 450 | Out-Null
    Start-Sleep -Milliseconds 650
    $ui = Dump-UiText -LocalPath $uiPath
    if ($ui -match "Virtual Hardware Controls") {
        $foundControls = $true
        break
    }
}
if (-not $foundControls) {
    throw "Virtual controls section not found after scroll."
}
if ($ui -notmatch "UP" -or $ui -notmatch "OK" -or $ui -notmatch "ENCODER") {
    throw "Expected virtual controls (UP/OK/ENCODER) not found."
}

if ($RequireConnected) {
    Write-Host "RequireConnected set. Validating live controls..."
    # Return to top to read Last control, then back to controls for tapping.
    & $script:AdbPath -s $script:DeviceSerial shell input swipe 720 900 720 2600 450 | Out-Null
    Start-Sleep -Milliseconds 700
    $uiTop = Dump-UiText -LocalPath $uiPath

    if ($uiTop -match "Not connected") {
        if ($uiTop -match "No USB serial device found") {
            if ($uiTop -match "USB [0-9a-fA-F]{4}:[0-9a-fA-F]{4} needs permission") {
                Write-Host "USB device detected but permission is missing. Requesting permission..."
                [void](Tap-ByText -UiXml $uiTop -Text "USB 1d50:6018 needs permission")
                Start-Sleep -Milliseconds 500
                $uiTop = Dump-UiText -LocalPath $uiPath
            } else {
                throw "Expected Connected state, but no USB serial device was detected. Connect HackRF/PortaPack to phone OTG first."
            }
        }
        Write-Host "Not connected. Attempting auto-connect..."
        $tappedConnect = Tap-ByText -UiXml $uiTop -Text "Connect"
        if (-not $tappedConnect) {
            throw "Expected Connected state, but could not find Connect button in UI."
        }

        $connected = $false
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Milliseconds 700
            $uiTop = Dump-UiText -LocalPath $uiPath
            if ($uiTop -match "Not connected") {
                if ($uiTop -match "Allow") {
                    Tap-ByText -UiXml $uiTop -Text "Allow" | Out-Null
                }
                continue
            }
            if ($uiTop -match "\bConnected\b") {
                $connected = $true
                break
            }
        }
        if (-not $connected) {
            $logText = (& $script:AdbPath -s $script:DeviceSerial logcat -d | Out-String)
            if ($logText -match "SerialTimeoutException|Connect failed: USB busy|rc=-1") {
                throw "Expected Connected state, but USB serial was busy. Close competing SDR apps (marto.rtl_tcp_andro / com.s33me.myhackrf), reconnect OTG, then retry."
            }
            throw "Expected Connected state, auto-connect attempt did not reach Connected."
        }
    } elseif ($uiTop -notmatch "\bConnected\b") {
        throw "Expected Connected state, but UI does not show Connected."
    }

    & $script:AdbPath -s $script:DeviceSerial shell input swipe 720 2600 720 900 450 | Out-Null
    Start-Sleep -Milliseconds 700
    $uiControls = Dump-UiText -LocalPath $uiPath
    $tapTargets = @("UP")
    foreach ($t in $tapTargets) {
        if (-not (Tap-ByText -UiXml $uiControls -Text $t)) {
            throw "Could not tap '$t' because it was not found in the UI dump."
        }
        $uiControls = Dump-UiText -LocalPath $uiPath
    }

    & $script:AdbPath -s $script:DeviceSerial shell input swipe 720 900 720 2600 450 | Out-Null
    Start-Sleep -Milliseconds 700
    $uiTopAfter = Dump-UiText -LocalPath $uiPath
    $after = Get-LastControlValue -UiXml $uiTopAfter
    if ($null -eq $after -or $after -notmatch "UP") {
        throw "Last control did not update to UP after virtual control tap."
    }
    Write-Host "Live control validation passed. Last control: $after"
}

# Basic crash gate
$logText = (& $script:AdbPath -s $script:DeviceSerial logcat -d | Out-String)
$pkgPattern = "Process:\s*" + [regex]::Escape($PackageName)
if ($logText -match $pkgPattern) {
    throw "Crash signatures found in logcat. Review logcat output."
}

Write-Host "QA PASS: UI + control surface checks completed."
