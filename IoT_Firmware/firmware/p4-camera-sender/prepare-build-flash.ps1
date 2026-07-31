[CmdletBinding()]
param(
    [string]$Port = "",
    [string]$IdfVersion = "v5.5.4",
    [switch]$BuildOnly,
    [switch]$NoMonitor
)

$ErrorActionPreference = "Stop"

function Invoke-EimIdf {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command
    )

    & $script:EimPath run $Command $script:IdfVersion --do-not-track true
    if ($LASTEXITCODE -ne 0) {
        throw "ESP-IDF command failed with exit code $LASTEXITCODE`: $Command"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = if ($env:P4_SENDER_WINDOWS_BUILD_DIR) {
    $env:P4_SENDER_WINDOWS_BUILD_DIR
}
else {
    "C:\tmp\p4sender"
}
$defaultsPath = Join-Path $scriptDir "overlay\sdkconfig.defaults"
$overridesPath = Join-Path $scriptDir "overlay\main"
$script:IdfVersion = $IdfVersion

$eimCandidates = @(
    (Get-Command eim -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Espressif.EIM-CLI_Microsoft.Winget.Source_8wekyb3d8bbwe\eim.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

if (-not $eimCandidates) {
    throw "Espressif EIM was not found. Install it with: winget install Espressif.EIM-CLI"
}
$script:EimPath = @($eimCandidates)[0]

if (-not (Test-Path -LiteralPath (Join-Path $projectDir "CMakeLists.txt"))) {
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) (
        "p4-sender-" + [Guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        Push-Location $tempDir
        try {
            Invoke-EimIdf -Command (
                "idf.py create-project-from-example " +
                '"espressif/esp_video=2.3.0:m2m"'
            )
        }
        finally {
            Pop-Location
        }

        $generatedCmake = Get-ChildItem -LiteralPath $tempDir `
            -Recurse -Filter CMakeLists.txt -File |
            Where-Object {
                Test-Path -LiteralPath (Join-Path $_.Directory.FullName "main")
            } |
            Select-Object -First 1

        if (-not $generatedCmake) {
            throw "Could not locate the generated esp_video m2m project."
        }

        New-Item -ItemType Directory -Path (Split-Path -Parent $projectDir) `
            -Force | Out-Null
        Copy-Item -LiteralPath $generatedCmake.Directory.FullName `
            -Destination $projectDir -Recurse
        Copy-Item -LiteralPath $defaultsPath `
            -Destination (Join-Path $projectDir "sdkconfig.defaults") -Force

        $manifestPath = Join-Path $projectDir "main\idf_component.yml"
        $manifest = [System.IO.File]::ReadAllText($manifestPath)
        $unpinned = "  esp_video: {}"
        $pinned = "  esp_video:`n    version: `"2.3.0`""
        if (-not $manifest.Contains($unpinned)) {
            throw "Could not pin esp_video in $manifestPath"
        }
        [System.IO.File]::WriteAllText(
            $manifestPath,
            $manifest.Replace($unpinned, $pinned),
            [System.Text.UTF8Encoding]::new($false)
        )
    }
    finally {
        if (Test-Path -LiteralPath $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force
        }
    }
}

Copy-Item -LiteralPath $defaultsPath -Destination (Join-Path $projectDir "sdkconfig.defaults") -Force

$mainDir = Join-Path $projectDir "main"
Get-ChildItem -LiteralPath $overridesPath -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $mainDir -Force
}

# sdkconfig.defaults only seeds a new build. Keep our project-specific settings
# deterministic when an existing ESP-IDF build directory is reused.
$sdkconfigPath = Join-Path $projectDir "sdkconfig"
if (Test-Path -LiteralPath $sdkconfigPath) {
    $sdkconfig = [System.IO.File]::ReadAllText($sdkconfigPath)
    $defaults = [System.IO.File]::ReadAllText($defaultsPath)

    $managedConfigPattern = "^CONFIG_(P4SENDER_|CAMERA_OV5647_MIPI_|ESP_VIDEO_ENABLE_(H264_VIDEO_DEVICE|HW_H264_VIDEO_DEVICE|JPEG_ENC_VIDEO_DEVICE|HW_JPEG_ENC_VIDEO_DEVICE)$)"
    foreach ($line in ($defaults -split "\r?\n")) {
        if ($line -notmatch "^(CONFIG_[A-Z0-9_]+)=(.*)$") {
            continue
        }
        $key = $Matches[1]
        $value = $Matches[2]
        if ($key -notmatch $managedConfigPattern) {
            continue
        }

        $setPattern = "(?m)^" + [regex]::Escape($key) + "=.*$"
        $unsetPattern = "(?m)^# " + [regex]::Escape($key) + " is not set$"
        $combinedPattern = $setPattern + "|" + $unsetPattern
        $replacement = if ($value -eq "n") {
            "# $key is not set"
        }
        else {
            $line
        }

        if ([regex]::IsMatch($sdkconfig, $combinedPattern)) {
            $sdkconfig = [regex]::Replace($sdkconfig, $combinedPattern, $replacement)
        }
        else {
            $sdkconfig = $sdkconfig.TrimEnd() + "`r`n" + $replacement + "`r`n"
        }
    }

    [System.IO.File]::WriteAllText(
        $sdkconfigPath,
        $sdkconfig,
        [System.Text.UTF8Encoding]::new($false)
    )
}
Push-Location $projectDir
try {
    if (-not (Test-Path -LiteralPath "sdkconfig")) {
        Invoke-EimIdf -Command "idf.py set-target esp32p4"
    }

    Invoke-EimIdf -Command "idf.py build"
    $appBinary = Join-Path $projectDir "build\m2m.bin"
    if (-not (Test-Path -LiteralPath $appBinary)) {
        throw "ESP-IDF build did not produce $appBinary. Inspect build\log for the compiler error."
    }

    if ($BuildOnly) {
        Write-Host "Build completed: $projectDir"
        exit 0
    }

    if (-not $Port) {
        Write-Host ""
        Write-Host "Build completed. Connect the board's FUSB port and run:"
        Write-Host "  .\prepare-build-flash.ps1 -Port COM10"
        Write-Host ""
        Write-Host "Detected serial ports:"
        Get-CimInstance Win32_SerialPort -ErrorAction SilentlyContinue |
            Select-Object DeviceID, Name
        exit 0
    }

    if ($NoMonitor) {
        Invoke-EimIdf -Command "idf.py -p $Port flash"
    }
    else {
        Invoke-EimIdf -Command "idf.py -p $Port flash monitor"
    }
}
finally {
    Pop-Location
}
