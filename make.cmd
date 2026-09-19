@echo off
REM Windows entrypoint so `make <target>` works without GNU make when this
REM directory is first on PATH, or when invoked as .\make.cmd <target>.
setlocal
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%scripts\sv.ps1" %*
exit /b %ERRORLEVEL%
