' [T-hub-startup-install] Install the PC hub executor + Ego bridge as logon
' entries, the safe way: .lnk shortcuts pointing AT the repo scripts.
'
' H-006 (2026-11-18): Startup used to hold a COPY of tools/hub/run_executor.vbs.
' That launcher resolves its sibling run_executor.cmd with
' GetParentFolderName(WScript.ScriptFullName); once copied into Startup it
' looked for the cmd next to ITSELF, found nothing, and executor.py never
' started. 127.0.0.1:8790 stayed closed, tailscale serve had nothing to proxy,
' and the phone-side hub channel looked like a network problem.
'
' This installer never copies a launcher. It creates shortcuts whose target is
' the repo copy of run_executor.vbs / run_bridge.vbs, so GetParentFolderName
' keeps resolving to tools\hub and tools\pchub. Re-running it is safe:
' shortcuts are overwritten and legacy copied launchers are renamed to
' *.removed.<timestamp> before the new entries are written.
'
' Usage:  cscript //nologo tools\hub\install_startup.vbs
'     or: tools\hub\install_startup.cmd  (/quiet skips the pause)
Option Explicit

Dim fso, sh
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")

Dim hubDir, pchubDir, startupDir, stamp
hubDir = fso.GetParentFolderName(WScript.ScriptFullName)
pchubDir = fso.GetParentFolderName(hubDir) & "\pchub"
If Not fso.FileExists(hubDir & "\run_executor.vbs") Then
    WScript.Echo "ERROR: run_executor.vbs not found next to this installer (" & hubDir & ")"
    WScript.Quit 1
End If
If Not fso.FileExists(pchubDir & "\run_bridge.vbs") Then
    WScript.Echo "ERROR: run_bridge.vbs not found at " & pchubDir
    WScript.Quit 1
End If

startupDir = sh.SpecialFolders("Startup")
If startupDir = "" Or Not fso.FolderExists(startupDir) Then
    WScript.Echo "ERROR: cannot resolve the Startup folder"
    WScript.Quit 1
End If
stamp = Year(Now) & Pad2(Month(Now)) & Pad2(Day(Now)) & "-" & Pad2(Hour(Now)) & Pad2(Minute(Now)) & Pad2(Second(Now))

' 1. Retire copied launchers. Only the two names this project owns; anything
'    else in Startup is left alone.
Dim legacy, name, removed
legacy = Array("MinisPCHubExecutor.vbs", "MinisPCHubBridge.vbs")
removed = 0
For Each name In legacy
    If fso.FileExists(startupDir & "\" & name) Then
        fso.MoveFile startupDir & "\" & name, startupDir & "\" & name & ".removed." & stamp
        removed = removed + 1
        WScript.Echo "removed legacy copy: " & name & " -> " & name & ".removed." & stamp
    End If
Next

' 2. Create the shortcuts that point at the REPO scripts.
MakeLink startupDir & "\MinisPCHubExecutor.lnk", hubDir & "\run_executor.vbs", hubDir, _
    "MiniPilot PC hub executor (executor.py on 127.0.0.1:8790)"
MakeLink startupDir & "\MinisPCHubBridge.lnk", pchubDir & "\run_bridge.vbs", pchubDir, _
    "MiniPilot Ego bridge (ego_mcp.py on 127.0.0.1:8788)"
WScript.Echo "installed: MinisPCHubExecutor.lnk -> " & hubDir & "\run_executor.vbs"
WScript.Echo "installed: MinisPCHubBridge.lnk -> " & pchubDir & "\run_bridge.vbs"

' 3. Self-check the hub port. Not listening: launch the executor once and give
'    it a few seconds; still dead: leave a clear line in executor.log so the
'    next boot is diagnosable without a console window.
If PortListening("127.0.0.1:8790") Then
    WScript.Echo "check: 127.0.0.1:8790 is LISTENING"
Else
    WScript.Echo "check: 127.0.0.1:8790 is not listening - starting the executor now"
    sh.Run "wscript.exe //nologo """ & hubDir & "\run_executor.vbs""", 0, False
    Dim i
    For i = 1 To 12
        WScript.Sleep 1000
        If PortListening("127.0.0.1:8790") Then Exit For
    Next
    If PortListening("127.0.0.1:8790") Then
        WScript.Echo "check: 127.0.0.1:8790 is LISTENING after launch"
    Else
        WScript.Echo "check: 127.0.0.1:8790 is STILL not listening - see " & hubDir & "\executor.log"
        sh.Run "cmd /c echo [install_startup] " & stamp & _
            " 127.0.0.1:8790 is not LISTENING after launch - executor did not start >> """ & _
            hubDir & "\executor.log""", 0, True
    End If
End If

WScript.Echo "done. Legacy copies retired: " & removed & ". Reboot to verify: no popup, 8790 handled by the .lnk."
WScript.Quit 0

' ---------------------------------------------------------------------------

Sub MakeLink(linkPath, targetVbs, workDir, desc)
    Dim lnk, wscriptExe
    wscriptExe = sh.ExpandEnvironmentStrings("%WINDIR%") & "\System32\wscript.exe"
    Set lnk = sh.CreateShortcut(linkPath)
    lnk.TargetPath = wscriptExe
    lnk.Arguments = "//nologo """ & targetVbs & """"
    lnk.WorkingDirectory = workDir
    lnk.Description = desc
    lnk.IconLocation = wscriptExe & ",0"
    lnk.Save
End Sub

Function PortListening(address)
    ' findstr exits 0 on a match. The pattern is the run_executor.cmd probe
    ' shape: the address followed by anything and LISTENING on the same line.
    PortListening = (sh.Run("cmd /c netstat -ano | findstr /R /C:""" & address & " .*LISTENING""", 0, True) = 0)
End Function

Function Pad2(n)
    Pad2 = Right("0" & n, 2)
End Function
