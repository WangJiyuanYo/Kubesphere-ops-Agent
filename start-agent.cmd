@echo off
setlocal
cd /d "%~dp0"
set "AGENT_JAVA=java"
if defined JAVA_HOME set "AGENT_JAVA=%JAVA_HOME%\bin\java.exe"
"%AGENT_JAVA%" -jar "target\deployment-ops-agent-0.1.0-SNAPSHOT.jar" --spring.profiles.active=sqlite --server.address=127.0.0.1 --server.port=8100 --agent.mode=DEMO --agent.auto-retry=false --agent.platform-catalog.enabled=false --agent.provisioning.enabled=false --agent.kubectl.enabled=false
set "AGENT_EXIT=%ERRORLEVEL%"
if not "%AGENT_EXIT%"=="0" pause
exit /b %AGENT_EXIT%
