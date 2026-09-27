@echo off
chcp 65001 >nul
cd /d "%~dp0"
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 goto failed
rem Spring Boot lives in lib\spring: keep those jars on the classpath (never merge them into one jar,
rem same-named META-INF entries would be clobbered and Boot would start as a non-web application).
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 "-Dbot.home=%~dp0." -cp "%~dp0build\pixiko.jar;%~dp0lib\gson-2.13.1.jar;%~dp0lib\spring\*" cn.szu.bot.Main %*
if errorlevel 1 goto failed
exit /b 0
:failed
echo Startup or build failed. See the message above.
pause
exit /b 1
