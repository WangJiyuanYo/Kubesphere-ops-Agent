@echo off
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  powershell.exe -NoProfile -File "%~dp0update-local-readonly-agent.ps1" %*
) else (
  pwsh.exe -NoProfile -File "%~dp0update-local-readonly-agent.ps1" %*
)
exit /b %errorlevel%
