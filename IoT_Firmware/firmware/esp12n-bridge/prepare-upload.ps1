[CmdletBinding()]
param(
    [string]$Port = "",
    [switch]$BuildOnly
)

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$secretsPath = Join-Path $projectDir "include\secrets.h"
$templatePath = Join-Path $projectDir "include\secrets.example.h"

$pioCommand = Get-Command pio -ErrorAction SilentlyContinue
if (-not $pioCommand) {
    $pioCommand = Get-Command platformio -ErrorAction SilentlyContinue
}

if ($pioCommand) {
    $pioExecutable = $pioCommand.Source
}
elseif (Test-Path -LiteralPath "C:\tmp\iotcam-pio-venv\Scripts\platformio.exe") {
    $pioExecutable = "C:\tmp\iotcam-pio-venv\Scripts\platformio.exe"
}
else {
    throw @"
PlatformIO was not found. Install it once with:
  py -m pip install --user platformio
Then reopen PowerShell and run this script again.
"@
}

if (-not (Test-Path -LiteralPath $secretsPath)) {
    if ($BuildOnly) {
        Write-Warning "include\secrets.h is absent; compiling with placeholder values."
    }
    else {
        throw @"
Create the private settings file first:
  Copy-Item '$templatePath' '$secretsPath'
Edit WIFI_SSID, WIFI_PASSWORD, and JETSON_HOST, then retry.
"@
    }
}

Push-Location $projectDir
try {
    & $pioExecutable run -e esp12n
    if ($LASTEXITCODE -ne 0) {
        throw "PlatformIO build failed with exit code $LASTEXITCODE"
    }

    if ($BuildOnly) {
        $binary = Join-Path $projectDir ".pio\build\esp12n\firmware.bin"
        Write-Host "Build completed: $binary"
        return
    }

    if (-not $Port) {
        Write-Host "Detected serial ports:"
        Get-CimInstance Win32_SerialPort -ErrorAction SilentlyContinue |
            Select-Object DeviceID, Name
        throw "Specify the ESP USB-UART port, for example: -Port COM11"
    }

    & $pioExecutable run -e esp12n --target upload --upload-port $Port
    if ($LASTEXITCODE -ne 0) {
        throw "PlatformIO upload failed with exit code $LASTEXITCODE"
    }

    Write-Host "ESP-12N upload completed on $Port"
}
finally {
    Pop-Location
}
