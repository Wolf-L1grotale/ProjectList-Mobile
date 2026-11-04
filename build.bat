@echo off
rem Double-click this file (or run it from cmd) to build the APK with one button.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
pause
