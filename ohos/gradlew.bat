@echo off
setlocal
set "PORT_JAVA=java.exe"
if defined JAVA_HOME set "PORT_JAVA=%JAVA_HOME%\bin\java.exe"
"%PORT_JAVA%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
set "PORT_EXIT=%ERRORLEVEL%"
endlocal & exit /b %PORT_EXIT%
