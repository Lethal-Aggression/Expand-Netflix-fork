@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat assembleDebug testDebugUnitTest lintDebug
exit /b %errorlevel%
