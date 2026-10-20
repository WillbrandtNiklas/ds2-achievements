@echo off
rem Einmaliges Setup: liest Item-Fundorte aus deiner Spielinstallation (nur lesend) nach extracted\items.json.
rem Optional: Spielordner als Argument, z. B. setup.bat "D:\SteamLibrary\steamapps\common\Dark Souls II Scholar of the First Sin\Game"
cd /d "%~dp0"
if not exist target\ds2-achievements.jar (
  call build.bat || exit /b 1
)
java -cp target\ds2-achievements.jar ds2ach.setup.Setup %*
pause
