[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^COM\d+$")]
    [string]$Port,

    [ValidateSet("Probe", "Backup", "Flash", "Restore")]
    [string]$Action = "Probe",

    [string]$BackupFile = ""
)

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = "C:\tmp\iotcam-pio-venv\Scripts\python.exe"
$esptool = "C:\Users\baak_jun\.platformio\packages\tool-esptoolpy\esptool.py"
$pio = "C:\tmp\iotcam-pio-venv\Scripts\pio.exe"
$firmware = Join-Path $projectDir ".pio\build\dt06\firmware.bin"
$backupDir = Join-Path $projectDir "backups"
$expectedFlashSize = 2MB

foreach ($required in @($python, $esptool, $pio)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required tool not found: $required"
    }
}

function Invoke-Esptool {
    param([string[]]$Arguments)
    & $python $esptool --chip esp8266 --port $Port --baud 115200 @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "esptool failed with exit code $LASTEXITCODE"
    }
}

function Show-BootloaderReminder {
    Write-Host ""
    Write-Host "DT-06 must already be in download mode:"
    Write-Host "  1) Hold G (FLASH/GPIO0)"
    Write-Host "  2) Tap and release R (RESET)"
    Write-Host "  3) Release G"
    Write-Host ""
}

if ($Action -eq "Probe") {
    Show-BootloaderReminder
    $output = & $python $esptool --chip esp8266 --port $Port --baud 115200 flash_id 2>&1
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Could not communicate with DT-06. Check boot mode and COM port."
    }
    $text = $output -join "`n"
    if ($text -notmatch "Features:\s*WiFi, Embedded Flash" -or
        $text -notmatch "Detected flash size:\s*2MB") {
        throw "Target is not the expected 2MB ESP8285 DT-06; refusing to continue."
    }
    Write-Host "Probe complete. No flash contents were changed."
    exit 0
}

if ($Action -eq "Backup") {
    Show-BootloaderReminder
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $BackupFile = Join-Path $backupDir "dt06-stock-$stamp-$expectedFlashSize.bin"
    try {
        Invoke-Esptool @(
            "read_flash", "0x0",
            "0x$($expectedFlashSize.ToString('X'))", $BackupFile
        )
    }
    catch {
        if (Test-Path -LiteralPath $BackupFile) {
            Remove-Item -LiteralPath $BackupFile -Force
        }
        throw
    }

    $actualSize = (Get-Item -LiteralPath $BackupFile).Length
    if ($actualSize -ne $expectedFlashSize) {
        Remove-Item -LiteralPath $BackupFile -Force
        throw "Backup size mismatch: expected $expectedFlashSize, got $actualSize"
    }
    $hash = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash
    Write-Host "Backup complete: $BackupFile"
    Write-Host "SHA256: $hash"
    Write-Host "Keep this file private; it may contain saved Wi-Fi credentials."
    exit 0
}

if ($Action -eq "Flash") {
    if (-not (Test-Path -LiteralPath $BackupFile)) {
        throw "Flash requires -BackupFile pointing to a verified full stock backup."
    }
    if ((Get-Item -LiteralPath $BackupFile).Length -ne $expectedFlashSize) {
        throw "Backup file must be exactly $expectedFlashSize bytes."
    }

    Push-Location $projectDir
    try {
        & $pio run -e dt06
        if ($LASTEXITCODE -ne 0) {
            throw "DT-06 firmware build failed."
        }
    }
    finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $firmware)) {
        throw "Built firmware was not found: $firmware"
    }

    Show-BootloaderReminder
    Invoke-Esptool @(
        "write_flash",
        "--flash_mode", "dout",
        "--flash_freq", "40m",
        "--flash_size", "2MB",
        "0x0", $firmware
    )
    Write-Host "DT-06 custom firmware flashed; esptool verified the written data hash."
    Write-Host "Tap R once to boot normally, then join IoTCam-Setup-xxxx."
    exit 0
}

if ($Action -eq "Restore") {
    if (-not (Test-Path -LiteralPath $BackupFile)) {
        throw "Restore requires -BackupFile pointing to the full stock backup."
    }
    if ((Get-Item -LiteralPath $BackupFile).Length -ne $expectedFlashSize) {
        throw "Backup file must be exactly $expectedFlashSize bytes."
    }
    Show-BootloaderReminder
    Invoke-Esptool @(
        "write_flash",
        "--flash_mode", "dout",
        "--flash_freq", "40m",
        "--flash_size", "keep",
        "0x0", $BackupFile
    )
    Write-Host "Stock DT-06 backup restored; esptool verified the written data hash."
    Write-Host "Tap R once to boot normally."
}