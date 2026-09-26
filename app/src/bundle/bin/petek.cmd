@echo off
rem
rem Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
rem Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
rem
rem Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
rem compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
rem Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
rem
rem Launcher of the Windows bundle: runs Pətək on the Java runtime shipped in ..\runtime, so the machine needs no
rem JDK. Works from any directory; extra JVM options go in PETEK_OPTS.

setlocal
set "APP_HOME=%~dp0.."
set "JAVA=%APP_HOME%\runtime\bin\java.exe"
if not exist "%JAVA%" (
    echo petek: the bundled Java runtime is missing at "%JAVA%" ^(extract the whole archive, not only bin\^) 1>&2
    exit /b 1
)

"%JAVA%" --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Dpetek.home=%APP_HOME%" %PETEK_OPTS% -cp "%APP_HOME%\lib\*" az.petek.app.MainKt %*
exit /b %ERRORLEVEL%
