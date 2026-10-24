@echo off
title DexoLink v4.1.0 (Windows)
echo Starting DexoLink Windows App...
python dexolink_gui.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Failed to start with 'python'. Trying with 'python3'...
    python3 dexolink_gui.py
)
pause
