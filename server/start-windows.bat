@echo off
REM Start the WiFi Mouse server on Windows. No dependencies are needed.
REM Pass extra options straight through, e.g. start-windows.bat -t 1234
setlocal
cd /d "%~dp0"
where py >nul 2>nul && (py -3 wifimouse_server.py %*) || (python wifimouse_server.py %*)
pause
