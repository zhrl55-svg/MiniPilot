@echo off
rem Keep the Ego Bridge alive: restart it a few seconds after any exit.
rem If another instance already owns 8788 (manual run + logon task), wait
rem instead of racing it for the port.
cd /d "%~dp0"
:loop
netstat -ano | findstr /R /C:"127.0.0.1:8788 .*LISTENING" >nul 2>&1
if not errorlevel 1 (
  timeout /t 15 /nobreak >nul
  goto loop
)
python ego_mcp.py --port 8788 >> ego_mcp.log 2>&1
timeout /t 5 /nobreak >nul
goto loop
