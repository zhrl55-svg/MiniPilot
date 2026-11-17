' Start run_bridge.cmd with no console window (scheduled-task entry point).
Dim fso, shell, dir
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.Run """" & dir & "\run_bridge.cmd""", 0, False
