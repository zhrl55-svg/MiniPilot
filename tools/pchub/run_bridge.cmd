@echo off
rem Keep the Ego Bridge alive: restart it a few seconds after any exit.
cd /d "%~dp0"
:loop
python ego_mcp.py --port 8788 >> ego_mcp.log 2>&1
timeout /t 5 /nobreak >nul
goto loop
