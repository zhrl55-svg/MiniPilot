' Start run_executor.cmd with no console window (logon entry point).
Dim fso, shell, dir
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.Run """" & dir & "\run_executor.cmd""", 0, False
