@echo off
REM Host fallback on Windows — delegates to make.cmd dev (PowerShell orchestrator).
setlocal
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%sv.ps1" dev
exit /b %ERRORLEVEL%
