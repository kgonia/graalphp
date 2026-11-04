@echo off
rem Per-LP load on the Windows host (lpload.ps1), off LPs 14-21, until the file STOP exists: lpload.cmd STOP OUTFILE
start "" /b /wait /affinity C03FFF powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lpload.ps1" -Stop %1 > %2 2>&1
