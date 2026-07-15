' Launches the Sonos Lyrics server with no visible console window.
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\Obinna\sonos-lyrics"
' 0 = hidden window, False = don't wait
sh.Run """C:\Program Files\nodejs\node.exe"" server.js", 0, False
