@echo off
rem Watchdog: bring the ego service back when it is not running.
rem Scheduled task MinisEgoWatch runs this every minute.
set "HERE=D:\minis-src\tools\hub\hubroot"
set "PY=C:\Users\zhrl5\miniconda3\pythonw.exe"
set "PID="
if exist "%HERE%\ego_srv.pid" set /p PID=<"%HERE%\ego_srv.pid"
if defined PID (
  tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul && exit /b 0
)
start "" /b "%PY%" "%HERE%\ego_srv.py" >nul 2>&1
