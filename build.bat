@echo off
chcp 936 >nul
title Kiosk 广告机 - 编译工具
python "%~dp0build.py"
pause
