@echo off
cd /d "%~dp0"
where py >nul 2>&1 && py -3 IrisPC.pyw && exit /b
where python >nul 2>&1 && python IrisPC.pyw && exit /b
echo Python 3 is required. Install from https://www.python.org/downloads/
pause
