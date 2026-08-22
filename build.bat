@echo off
REM ============ Windows 打包入口 ============
REM 仅作双击启动用,实际逻辑在 build.ps1(正确处理 UTF-8 中文应用名)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
exit /b %errorlevel%
