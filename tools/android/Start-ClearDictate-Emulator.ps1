<#
.SYNOPSIS
Starts the project-local ClearDictate Android emulator and waits until Android finishes booting.

.PARAMETER Headless
Runs without a window or host audio for automated checks.
#>
param(
    [switch]$Headless
)

$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$androidSdk = Join-Path $repositoryRoot '.tooling\android-sdk'
$emulatorLauncher = Join-Path $androidSdk 'emulator\emulator.exe'
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
$avdHome = Join-Path $repositoryRoot '.tooling\android-avd'
$androidUserHome = Join-Path $repositoryRoot '.tooling\android-user'
$avdName = 'ClearDictate_API_35'

if (-not (Test-Path -LiteralPath $emulatorLauncher) -or -not (Test-Path -LiteralPath $adb))
{
    throw 'The project-local Android emulator is not installed. Expected .tooling\android-sdk.'
}

if (-not (Test-Path -LiteralPath (Join-Path $avdHome "$avdName.avd\config.ini")))
{
    throw "The $avdName virtual device is not installed under .tooling\android-avd."
}

$attachedEmulator = & $adb devices | Select-String '^emulator-\d+\s+device$'
if ($attachedEmulator)
{
    Write-Host 'The ClearDictate emulator is already running.'
    exit 0
}

$env:ANDROID_AVD_HOME = $avdHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$env:ANDROID_USER_HOME = $androidUserHome
$env:ANDROID_EMULATOR_HOME = $androidUserHome
New-Item -ItemType Directory -Force -Path $androidUserHome | Out-Null
$processPath = $env:Path
Remove-Item Env:PATH -ErrorAction SilentlyContinue
$env:Path = $processPath
$arguments = @('-avd', $avdName, '-no-boot-anim', '-no-snapshot-save', '-gpu', 'swiftshader_indirect')
if ($Headless)
{
    $arguments += @('-no-window', '-no-audio')
}

# A visible window is intentional for manual microphone and keyboard testing.
$startParameters = @{ FilePath = $emulatorLauncher; ArgumentList = $arguments; PassThru = $true }
if ($Headless)
{
    $startParameters.WindowStyle = 'Hidden'
}
$null = Start-Process @startParameters
while (-not (& $adb devices | Select-String '^emulator-\d+\s+device$'))
{
    Start-Sleep -Seconds 1
}
while ((& $adb shell getprop sys.boot_completed 2>$null).Trim() -ne '1')
{
    Start-Sleep -Seconds 1
}

Write-Host "ClearDictate emulator $avdName is ready."
