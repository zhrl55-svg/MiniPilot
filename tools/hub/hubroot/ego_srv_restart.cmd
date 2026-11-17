@echo off
rem Restart the ego service (no console window).
set "HERE=D:\minis-src\tools\hub\hubroot"
set "PY=C:\Users\zhrl5\miniconda3\pythonw.exe"
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8799" ^| findstr "LISTENING"') do taskkill /f /pid %%a >nul 2>&1
timeout /t 1 /nobreak >nul
start "" /b "%PY%" "%HERE%\ego_srv.py" >nul 2>&1
