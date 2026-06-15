param(
    [string]$DeviceSerial = "",
    [string]$ModelPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$model = if ($ModelPath.Trim().Length -gt 0) {
    if ([System.IO.Path]::IsPathRooted($ModelPath)) { $ModelPath } else { Join-Path $root $ModelPath }
} else {
    Join-Path $root "models\gemma-4-E4B-it.litertlm"
}
$packageName = "com.example.counseling"
$remoteDir = "/sdcard/Android/data/$packageName/files/models"
$remoteModel = "$remoteDir/$([System.IO.Path]::GetFileName($model))"

if (!(Test-Path $adb)) {
    throw "adb.exe was not found at $adb"
}
if (!(Test-Path $apk)) {
    throw "Debug APK was not found. Run .\gradlew.bat :app:assembleDebug first."
}
if (!(Test-Path $model)) {
    throw "LiteRT-LM model was not found at $model"
}

$adbArgs = @()
if ($DeviceSerial.Trim().Length -gt 0) {
    $adbArgs += @("-s", $DeviceSerial)
}

& $adb @adbArgs "install" "-r" $apk
& $adb @adbArgs "shell" "mkdir" "-p" $remoteDir
& $adb @adbArgs "push" $model $remoteModel

Write-Host "Installed $apk"
Write-Host "Pushed $model to $remoteModel"
