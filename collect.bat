@echo off
REM Double-click this to start the live bazaar collector.
REM Leave the window open; close it or press Ctrl-C to stop.
title shardfuse collector
cd /d "%~dp0"
python scripts\collect.py
echo.
echo Collector stopped.
pause
