@echo off
cd /d "%~dp0"
python "%~dp0start.py" --apk "%~dp0dist\PenDistance.apk" --skip-install
if errorlevel 1 (
  echo.
  echo Connect the tablet by USB and authorize USB debugging, then retry.
)
pause
