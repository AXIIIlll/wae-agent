@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\User\AppData\Local\Android\Sdk"
cd /d "%~dp0"
call gradlew.bat assembleDebug assembleRelease --no-daemon
if exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0WAE.apk"
    if not exist "E:\apk" mkdir "E:\apk"
    copy /Y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "E:\apk\WAE.apk"
    copy /Y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "C:\Users\User\Downloads\WAE.apk"
)
