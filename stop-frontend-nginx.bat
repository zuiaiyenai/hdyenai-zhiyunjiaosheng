@echo off
setlocal EnableExtensions

set "PROJECT_ROOT=%~dp0"
set "NGINX_PREFIX=%PROJECT_ROOT%deploy\nginx"
set "NGINX_CONF=conf/nginx.conf"

call :find_nginx
if not defined NGINX_BIN (
    echo [ERROR] nginx.exe was not found.
    exit /b 1
)

if not exist "%NGINX_PREFIX%\logs\nginx.pid" (
    echo [INFO] The project nginx instance is not running.
    exit /b 0
)

echo [INFO] Stopping the project nginx instance gracefully...
"%NGINX_BIN%" -p "%NGINX_PREFIX%/" -c "%NGINX_CONF%" -s quit
if errorlevel 1 (
    echo [ERROR] nginx stop failed. Check deploy\nginx\logs\error.log.
    exit /b 1
)
echo [OK] Stop signal sent.
exit /b 0

:find_nginx
if defined NGINX_EXE if exist "%NGINX_EXE%" set "NGINX_BIN=%NGINX_EXE%"
if defined NGINX_BIN exit /b 0
for /f "delims=" %%N in ('where nginx.exe 2^>nul') do if not defined NGINX_BIN set "NGINX_BIN=%%N"
if defined NGINX_BIN exit /b 0
for %%N in (
    "%PROJECT_ROOT%tools\nginx\nginx.exe"
    "D:\code\webaicode\nginx-1.22.0-web\nginx.exe"
    "D:\code\cangqiong\nginx-1.20.2\nginx.exe"
    "D:\quarkdownload\nginx-1.20.2\nginx.exe"
    "D:\code\heimadianping\nginx-1.18.0\nginx.exe"
    "D:\BaiduNetdiskDownload\heimadianping\nginx-1.18.0\nginx.exe"
    "D:\phpstudy_pro\Extensions\Nginx1.15.11\nginx.exe"
) do if not defined NGINX_BIN if exist "%%~fN" set "NGINX_BIN=%%~fN"
exit /b 0
