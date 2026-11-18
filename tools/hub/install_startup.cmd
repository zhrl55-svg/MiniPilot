@echo off
rem [T-hub-startup-install] Install the logon shortcuts for the PC hub executor
rem and the Ego bridge. See install_startup.vbs and README.md ("开机自启").
rem Absolutely no copying of launcher scripts into Startup: the shortcuts point
rem at the repo copies so GetParentFolderName keeps finding their siblings.
cscript //nologo "%~dp0install_startup.vbs" %*
if /i not "%~1"=="/quiet" pause
