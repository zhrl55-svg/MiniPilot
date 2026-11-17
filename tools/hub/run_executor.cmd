@echo off
rem Keep the direct hub endpoint alive: the executor listens on 127.0.0.1:8790
rem and `tailscale serve --https=8443` exposes it to the tailnet only.
rem Restart a few seconds after any exit; if another instance already owns the
rem port (manual run), wait instead of racing it.
cd /d "%~dp0"
:loop
netstat -ano | findstr /R /C:"127.0.0.1:8790 .*LISTENING" >nul 2>&1
if not errorlevel 1 (
  timeout /t 15 /nobreak >nul
  goto loop
)
python executor.py --config config.listen.json --listen 127.0.0.1:8790 >> executor.log 2>&1
timeout /t 5 /nobreak >nul
goto loop
