@echo off
rem Baut target\ds2-achievements.jar. Sucht Maven im PATH, in MVN oder im Maven-Wrapper-Cache (~\.m2\wrapper).
cd /d "%~dp0"
if "%MVN%"=="" (
  where mvn >nul 2>nul && set MVN=mvn
)
if "%MVN%"=="" (
  for /f "delims=" %%m in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do set MVN=%%m
)
if "%MVN%"=="" (
  echo Maven nicht gefunden. Bitte Maven installieren oder MVN auf mvn.cmd setzen.
  pause
  exit /b 1
)
call "%MVN%" -q -B package -DskipTests || goto :fehler
echo Fertig: target\ds2-achievements.jar
exit /b 0
:fehler
echo Build fehlgeschlagen.
pause
exit /b 1
