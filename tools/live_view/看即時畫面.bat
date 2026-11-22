@echo off
REM 啟動即時監看儀表板（雙擊即可）
set PY=C:\Users\Wade\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
start "" http://127.0.0.1:8765
"%PY%" "%~dp0server.py"
pause
