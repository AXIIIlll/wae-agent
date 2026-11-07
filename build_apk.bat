@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\User\AppData\Local\Android\Sdk"
cd /d E:\NewAndroidApp
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 exit /b %ERRORLEVEL%
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\NewAndroidApp\NewAndroidApp.apk"
if not exist "E:\apk" mkdir "E:\apk"
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\apk\NewAndroidApp.apk"
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "C:\Users\User\Downloads\NewAndroidApp.apk"
