' ===========================================================================
' KuangrenPC hidden launcher (ASCII only, required by VBScript)
' Starts KuangrenPC.ps1 with a completely hidden console window.
' Usage: wscript.exe Start-KuangrenPC.vbs [extra powershell args, e.g. -DryRun]
' ===========================================================================
Option Explicit
Dim fso, shell, scriptDir, ps1, cmd, args, i

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
ps1 = fso.BuildPath(scriptDir, "KuangrenPC.ps1")

If Not fso.FileExists(ps1) Then
    MsgBox "KuangrenPC.ps1 not found in " & scriptDir, 16, "KuangrenPC"
    WScript.Quit 2
End If

args = ""
For i = 0 To WScript.Arguments.Count - 1
    args = args & " " & WScript.Arguments(i)
Next

cmd = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & ps1 & """" & args

shell.CurrentDirectory = scriptDir
On Error Resume Next
shell.Run cmd, 0, False
If Err.Number <> 0 Then
    MsgBox "Failed to start KuangrenPC: " & Err.Description, 16, "KuangrenPC"
    WScript.Quit 3
End If
WScript.Quit 0
