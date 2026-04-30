@echo off
setlocal

if "%ECLIPSE_HOME%"=="" (
    echo [eqmd] ECLIPSE_HOME is not set 1>&2
    exit /b 1
)

if "%EQUIMACS_HOME%"=="" (
    set "EQUIMACS_HOME=%USERPROFILE%\.equimacs-headless"
)

if "%EQUIMACS_WORKSPACE%"=="" (
    set "EQUIMACS_WORKSPACE=%EQUIMACS_HOME%\workspace"
)

if "%EQUIMACS_SOCKET%"=="" (
    set "EQUIMACS_SOCKET=%EQUIMACS_HOME%\equimacs.sock"
)

if not exist "%EQUIMACS_HOME%" mkdir "%EQUIMACS_HOME%"

set "ECLIPSEC=%ECLIPSE_HOME%\eclipsec.exe"
if not exist "%ECLIPSEC%" set "ECLIPSEC=%ECLIPSE_HOME%\eclipse.exe"

set "EQMD_INI=%~dp0eqmd.ini"
set "JAVA_VM="
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\javaw.exe" (
        set "JAVA_VM=%JAVA_HOME%\bin\javaw.exe"
    ) else if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_VM=%JAVA_HOME%\bin\java.exe"
    )
)

if not "%JAVA_VM%"=="" (
    "%ECLIPSEC%" --launcher.ini "%EQMD_INI%" -nosplash -consoleLog ^
        -vm "%JAVA_VM%" ^
        -application org.equimacs.eclipse.app.application ^
        -data "%EQUIMACS_WORKSPACE%" ^
        %*
) else (
    "%ECLIPSEC%" --launcher.ini "%EQMD_INI%" -nosplash -consoleLog ^
        -application org.equimacs.eclipse.app.application ^
        -data "%EQUIMACS_WORKSPACE%" ^
        %*
)
