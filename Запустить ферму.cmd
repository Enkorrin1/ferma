@echo off
setlocal
cd /d "%~dp0source\mobile_poster_hub"
start "Панель фермы" "%~dp0source\mobile_poster_hub\.venv\Scripts\pythonw.exe" "%~dp0source\mobile_poster_hub\farm_control.pyw"
