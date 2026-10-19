@echo off
rem Startet die DS2 Achievement-Checkliste. Baut das JAR beim ersten Mal automatisch (braucht Java 21+ und Maven).
cd /d "%~dp0"
if not exist target\ds2-achievements.jar (
  call build.bat || exit /b 1
)
start "" javaw --enable-native-access=ALL-UNNAMED -jar target\ds2-achievements.jar
