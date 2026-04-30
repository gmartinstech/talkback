@echo off
REM Launch TalkBack AI Chat (Java 25)
cd /d "%~dp0"
if not exist "pom.xml" (
    echo Error: pom.xml not found. Please run from the repository root.
    pause
    exit /b 1
)

echo Stopping any running TalkBack instances...
taskkill /F /FI "COMMANDLINE eq *talkback*" 2>NUL

echo Building and launching TalkBack...
call mvn compile javafx:run -q
if errorlevel 1 (
    echo Build failed. Check output above for errors.
    pause
)
