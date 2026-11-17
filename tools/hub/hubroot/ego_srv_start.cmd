@echo off
rem Start the ego service (no console window). The watchdog task restarts it
rem whenever the port stops listening.
set "HERE=D:\minis-src\tools\hub\hubroot"
set "PY=C:\Users\zhrl5\miniconda3\pythonw.exe"
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8799" ^| findstr "LISTENING"') do taskkill /f /pid %%a >nul 2>&1
rem Redirect the child's output so the caller is not held
rem open by an inherited pipe (the service never exits).
start "" /b "%PY%" "%HERE%\ego_srv.py" >nul 2>&1
