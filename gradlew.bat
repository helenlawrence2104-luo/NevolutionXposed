@echo off
setlocal
if defined JAVA_HOME (set "JAVA_EXE=%JAVA_HOME%\bin\java.exe") else (set "JAVA_EXE=java.exe")
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
