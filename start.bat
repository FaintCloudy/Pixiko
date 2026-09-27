@echo off
rem ---------------------------------------------------------------------------
rem Pixiko v1.0.0 - "runnable package" launcher.
rem
rem Runs the PREBUILT jar that ships inside this package, so there is nothing to
rem compile. If you want to compile from source (or you are on a source
rem checkout), use run.bat instead - it runs build.ps1 and then starts.
rem
rem Requires: JDK 17 or newer on PATH (java.exe is all this script needs).
rem ---------------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0"

if not exist "%~dp0build\pixiko.jar" (
    echo [ERROR] build\pixiko.jar not found.
    echo         This launcher is meant for the runnable package, which ships a
    echo         prebuilt jar. On a source checkout run build.ps1 first, or simply
    echo         use run.bat ^(it builds and then starts^).
    pause
    exit /b 1
)

if not exist "%~dp0lib\spring" (
    echo [ERROR] lib\spring not found - the Spring Boot jars for the web console
    echo         are missing. See README.md for how to obtain them.
    pause
    exit /b 1
)

if not exist "%~dp0lib\gson-2.13.1.jar" (
    echo [ERROR] lib\gson-2.13.1.jar not found. build.ps1 downloads it automatically;
    echo         see README.md if you need to fetch it by hand.
    pause
    exit /b 1
)

rem Never merge lib\spring\*.jar into one archive: same-named entries
rem (META-INF/spring.factories, AutoConfiguration.imports) would be clobbered and
rem Spring Boot would silently start as a non-web application. Keep them on the
rem runtime classpath exactly like this instead.
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 "-Dbot.home=%~dp0." -cp "%~dp0build\pixiko.jar;%~dp0lib\gson-2.13.1.jar;%~dp0lib\spring\*" cn.szu.bot.Main %*
if errorlevel 1 goto failed
exit /b 0

:failed
echo Startup failed. See the message above.
pause
exit /b 1
