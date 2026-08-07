@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-ClearDictate-Debug.ps1" %*
exit /b %errorlevel%
