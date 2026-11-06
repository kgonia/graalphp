@echo off
rem Per-LP load on the Windows host (lpload.ps1), off LPs 14-21: lpload.cmd SECONDS OUTFILE
start "" /b /wait /affinity C03FFF powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lpload.ps1" -Seconds %1 > %2 2>&1
