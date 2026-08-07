@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-ClearDictate-Emulator.ps1" %*
exit /b %errorlevel%
