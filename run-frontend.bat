@echo off
REM Launch TalkBack PR Reviewer (Java 25)
cd /d "%~dp0"
if exist "pom.xml" (
    call mvnw.cmd javafx:run 2>NUL
    if errorlevel 1 call mvn javafx:run
) else (
    echo Error: pom.xml not found. Please run from the repository root.
    pause
)
