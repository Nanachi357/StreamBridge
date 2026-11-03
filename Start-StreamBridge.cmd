@echo off
setlocal
title StreamBridge
powershell.exe -NoLogo -NoProfile -File "%~dp0scripts\Start-StreamBridge.ps1"
if errorlevel 1 (
  echo.
  echo StreamBridge could not start. Read the error above.
  echo No system settings were changed. See README.md for setup.
  pause
)
