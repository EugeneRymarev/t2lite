@echo off
rem Adds the 4 release-signing secrets to GitHub (see github-secrets.ps1). Double-click it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0github-secrets.ps1" %*
echo.
pause
