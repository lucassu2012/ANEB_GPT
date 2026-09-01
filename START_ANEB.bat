@echo off
setlocal
set "ANEB_ROOT=%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ANEB_ROOT%tools\launch.ps1" %*
set "ANEB_RC=%ERRORLEVEL%"
if not "%ANEB_RC%"=="0" (
  echo.
  echo ANEB launcher stopped with exit code %ANEB_RC%.
)
exit /b %ANEB_RC%
