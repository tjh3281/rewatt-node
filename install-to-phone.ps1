# Builds ReWatt and installs it on the phone plugged in by USB (USB debugging on).
# Run from PowerShell in this folder:   .\install-to-phone.ps1
# The Tapo login comes from local.properties (not committed).

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

Write-Host "Phones connected:" -ForegroundColor Cyan
& $adb devices

.\gradlew.bat installDebug --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "Build or install FAILED (see above)." -ForegroundColor Red; exit 1 }

# A copy of the APK in Downloads, to share or install on another phone.
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "$env:USERPROFILE\Downloads\ReWatt-debug.apk" -Force

# Open the app on the phone (gives up after 10 s so the script never hangs).
$open = Start-Process $adb -ArgumentList "shell am start -n com.rewatt.app/.MainActivity" -NoNewWindow -PassThru
if (-not $open.WaitForExit(10000)) { $open.Kill(); Write-Host "(Open ReWatt on the phone yourself.)" }
Write-Host "Done: ReWatt installed and opened. APK copy: Downloads\ReWatt-debug.apk" -ForegroundColor Green
