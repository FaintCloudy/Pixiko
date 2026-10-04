@rem
@rem Pixiko Android Gradle wrapper launcher for Windows.
@rem
@rem NOTE: this file is intentionally pure ASCII. cmd.exe decodes .bat files with the
@rem OEM/ANSI code page, so UTF-8 (or UTF-16) non-ASCII bytes in a .bat get mangled into
@rem garbage that cmd then tries to execute ("'xxx' is not recognized as an internal or
@rem external command"). Keep every comment here in ASCII.
@rem
@rem Usage: gradlew.bat assembleDebug
@rem
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem #
@rem #  Gradle start up script for Windows
@rem #
@rem ##########################################################################

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe: prefer JAVA_HOME so we do not accidentally use a too-new JDK.
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
goto failNoJava

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
goto failNoJava

:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  "-Dorg.gradle.appname=%APP_BASE_NAME%" ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%

:failNoJava
echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
echo.
endlocal & exit /b 1
