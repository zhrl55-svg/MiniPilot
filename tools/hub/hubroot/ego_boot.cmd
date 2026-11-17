@echo off
rem Install the scheduled tasks for the ego service and start it now.
rem   MinisEgoSrv   - on logon: the resident service (pythonw, no window)
rem   MinisEgoWatch - every minute: watchdog, restarts the service if it died
set "HERE=D:\minis-src\tools\hub\hubroot"
set "PY=C:\Users\zhrl5\miniconda3\pythonw.exe"

schtasks /create /tn MinisEgoSrv /tr "\"%PY%\" \"%HERE%\ego_srv.py\"" /sc onlogon /rl limited /f >nul
schtasks /create /tn MinisEgoWatch /tr "\"%HERE%\ego_watchdog.cmd\"" /sc minute /mo 1 /rl limited /f >nul

call "%HERE%\ego_srv_start.cmd"
echo ego service installed: MinisEgoSrv (onlogon) + MinisEgoWatch (every minute)
