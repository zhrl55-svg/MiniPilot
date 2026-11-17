@echo off
rem Remove the temporary scripts left behind by the ego service.
del /q D:\minis-src\tools\hub\hubroot\ego_q_*.mjs 2>nul
del /q D:\minis-src\tools\hub\hubroot\ego_task.mjs 2>nul
del /q D:\minis-src\tools\hub\hubroot\ego_task2.mjs 2>nul
