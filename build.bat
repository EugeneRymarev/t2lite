@echo off
rem Wrapper for build.ps1: double-click it (shows a menu) or run from cmd/PowerShell with the same parameters.
rem Examples:  build.bat    build.bat -Install    build.bat -NoBuild    build.bat -Type debug -Install
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
