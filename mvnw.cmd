@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM ----------------------------------------------------------------------------
@IF "%DEBUG%"=="" @ECHO OFF
@SETLOCAL ENABLEEXTENSIONS
@SETLOCAL ENABLEDELAYEDEXPANSION

SET "BASE_DIR=%~dp0"
SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"

IF EXIST "%BASE_DIR%\.mvn\wrapper\maven-wrapper.jar" (
    java -Dmaven.multiModuleProjectDirectory="%BASE_DIR%." -cp "%BASE_DIR%\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
) ELSE (
    mvn %*
)
