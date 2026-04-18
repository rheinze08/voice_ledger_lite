@echo off
cd /d C:\Users\Roland\repos\voice_ledger_lite

echo Pulling from remote main...
git pull origin main

echo Building APK...
call gradlew.bat assembleRelease

echo Pushing to Git...
git add .
git commit -m "Auto-build: updated APK"
git push origin main

echo Uploading APK to Google Drive root...
rclone copy "app\build\outputs\apk\release\app-release.apk" "gdrive:/"

echo Done!
pause