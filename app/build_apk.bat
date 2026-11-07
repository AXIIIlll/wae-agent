@echo off
set JAVA_HOME=C:\Users\Wade\.jdks\jbr-21.0.11
set ANDROID_HOME=C:\Users\Wade\AppData\Local\Android\Sdk
cd /d E:\NewAndroidApp
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 exit /b %ERRORLEVEL%
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\NewAndroidApp\NewAndroidApp.apk"
if not exist "E:\apk" mkdir "E:\apk"
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\apk\NewAndroidApp.apk"
