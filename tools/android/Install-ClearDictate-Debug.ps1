<#
.SYNOPSIS
Builds the signed Debug APK, installs it on one connected Android device, and opens ClearDictate.

.PARAMETER Serial
Selects a device from `adb devices` when more than one is connected.
#>
param(
    [string]$Serial
)

$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$androidSdk = 'C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK'
$javaHome = 'C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
$adb = Join-Path $repositoryRoot '.tooling\android-sdk\platform-tools\adb.exe'
$gradleWrapper = Join-Path $repositoryRoot 'gradlew.bat'
$apk = Join-Path $repositoryRoot 'android-app\build\outputs\apk\debug\android-app-debug.apk'

if (-not (Test-Path -LiteralPath $adb))
{
    throw 'ADB is not installed at .tooling\android-sdk\platform-tools\adb.exe.'
}

$connectedDevices = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ([string]::IsNullOrWhiteSpace($Serial))
{
    if ($connectedDevices.Count -ne 1)
    {
        throw "Connect exactly one Android device or pass -Serial. Connected devices: $($connectedDevices -join ', ')"
    }
    $Serial = $connectedDevices[0]
}
elseif ($Serial -notin $connectedDevices)
{
    throw "Android device '$Serial' is not connected and authorized."
}

$env:ANDROID_HOME = $androidSdk
$env:JAVA_HOME = $javaHome
Push-Location $repositoryRoot
try
{
    & $gradleWrapper :android-app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0)
    {
        throw 'The Android Debug build failed.'
    }

    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0)
    {
        throw 'ADB could not install the ClearDictate APK.'
    }

    & $adb -s $Serial shell am start -n com.cleardictate.app.debug/com.cleardictate.android.MainActivity
}
finally
{
    Pop-Location
}

Write-Host "Installed and opened ClearDictate on $Serial."
