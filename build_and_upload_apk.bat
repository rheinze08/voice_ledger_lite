@echo off

REM Fix Java version (use Android Studio bundled JDK 17)
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d C:\Users\Roland\repos\voice_ledger_lite

echo Pulling from remote main...
git pull origin main

echo Building APK...
call gradlew.bat clean assembleRelease

echo Pushing to Git...
git add .
git commit -m "Auto-build: updated APK"
git push origin main

echo Uploading APK to Google Drive root...
rclone copy "app\build\outputs\apk\release\app-release.apk" "gdrive:/"

echo Done!
pause