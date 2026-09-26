@echo off
rem
rem Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
rem Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
rem
rem Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
rem compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
rem Unless required by applicable law or agreed to in writing, software distributed under the License is
rem distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and limitations under the License.
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
