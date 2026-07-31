[CmdletBinding()]
param(
    [string]$Port = "",
    [string]$IdfVersion = "v5.5.4",
    [switch]$BuildOnly,
    [switch]$NoMonitor,
    [switch]$KeepConfig
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
$projectDir = if ($env:P4_UVC_WINDOWS_BUILD_DIR) {
    $env:P4_UVC_WINDOWS_BUILD_DIR
}
else {
    "C:\tmp\p4uvc"
}
$defaultsPath = Join-Path $scriptDir "sdkconfig.defaults"
$overridesPath = Join-Path $scriptDir "overrides"
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
        "p4-uvc-" + [Guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        Push-Location $tempDir
        try {
            Invoke-EimIdf -Command (
                "idf.py create-project-from-example " +
                '"espressif/esp_video=2.2.0:uvc"'
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
            throw "Could not locate the generated esp_video UVC project."
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
        $pinned = "  esp_video:`n    version: `"2.2.0`""
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

$mainDir = Join-Path $projectDir "main"
Copy-Item -LiteralPath (Join-Path $overridesPath "uvc_example.c") `
    -Destination (Join-Path $mainDir "uvc_example.c") -Force
Copy-Item -LiteralPath (Join-Path $overridesPath "CMakeLists.txt") `
    -Destination (Join-Path $mainDir "CMakeLists.txt") -Force
Copy-Item -LiteralPath $defaultsPath `
    -Destination (Join-Path $projectDir "sdkconfig.defaults") -Force

Push-Location $projectDir
try {
    if (-not $KeepConfig) {
        # Recreate generated sdkconfig so a stale sensor selection cannot survive.
        if (Test-Path -LiteralPath "sdkconfig") {
            Remove-Item -LiteralPath "sdkconfig" -Force
        }
        Invoke-EimIdf -Command "idf.py set-target esp32p4"
    }
    elseif (-not (Test-Path -LiteralPath "sdkconfig")) {
        Invoke-EimIdf -Command "idf.py set-target esp32p4"
    }

    Invoke-EimIdf -Command "idf.py build"

    if ($BuildOnly) {
        Write-Host "Build completed: $projectDir"
        exit 0
    }

    if (-not $Port) {
        Write-Host ""
        Write-Host "Build completed. Connect the board's FUSB port and run:"
        Write-Host "  .\prepare-build-flash.ps1 -Port COM5"
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
