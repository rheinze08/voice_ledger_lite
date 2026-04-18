@echo off

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d C:\Users\Roland\repos\voice_ledger_lite

echo Stopping Gradle daemons...
call gradlew.bat --stop

echo Pulling from remote main...
git pull origin main || exit /b 1

echo Building APK...
call gradlew.bat assembleRelease --no-daemon || exit /b 1

echo Pushing to Git...
git add .
git diff --cached --quiet || git commit -m "Auto-build: updated APK"
git push origin main || exit /b 1

echo Uploading APK to Google Drive root...
"C:\Users\Roland\Downloads\rclone\rclone.exe" copy "app\build\outputs\apk\release" "datawiseguysllc-gdrive:/" --include "*.apk" || exit /b 1

echo Done!
pause