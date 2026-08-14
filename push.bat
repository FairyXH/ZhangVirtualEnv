@echo off
cd /d "%~dp0"
del /f /q *.log
git add .
git commit -m "Update"
git push