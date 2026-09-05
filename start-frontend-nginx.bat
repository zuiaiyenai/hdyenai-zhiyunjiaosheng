@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_ROOT=%~dp0"
set "FRONTEND_DIR=%PROJECT_ROOT%frontend"
set "NGINX_PREFIX=%PROJECT_ROOT%deploy\nginx"
set "NGINX_CONF=conf/nginx.conf"
set "FRONTEND_URL=http://127.0.0.1:8080"

call :find_nginx
if not defined NGINX_BIN (
    echo [ERROR] nginx.exe was not found.
    echo Set NGINX_EXE to its full path, or place nginx at tools\nginx\nginx.exe.
    exit /b 1
)

set "COMPLETE_FRONTEND_DIR=%PROJECT_ROOT%src\main\resources\static"
if not exist "%COMPLETE_FRONTEND_DIR%\index.html" (
    echo [ERROR] The feature-complete frontend bundle is missing: src\main\resources\static\index.html
    exit /b 1
)
echo [INFO] Serving the feature-complete frontend bundle from src\main\resources\static.

if not exist "%NGINX_PREFIX%\logs" mkdir "%NGINX_PREFIX%\logs"
if not exist "%NGINX_PREFIX%\temp\client_body_temp" mkdir "%NGINX_PREFIX%\temp\client_body_temp"
if not exist "%NGINX_PREFIX%\temp\proxy_temp" mkdir "%NGINX_PREFIX%\temp\proxy_temp"

echo [INFO] Using nginx: %NGINX_BIN%
"%NGINX_BIN%" -t -p "%NGINX_PREFIX%/" -c "%NGINX_CONF%"
if errorlevel 1 (
    echo [ERROR] nginx configuration validation failed.
    exit /b 1
)

set "NGINX_ACTION=start"
if exist "%NGINX_PREFIX%\logs\nginx.pid" (
    set /p NGINX_PID=<"%NGINX_PREFIX%\logs\nginx.pid"
    tasklist /FI "PID eq !NGINX_PID!" /NH 2>nul | findstr /R /C:"nginx.exe" >nul
    if not errorlevel 1 set "NGINX_ACTION=reload"
)

if "!NGINX_ACTION!"=="reload" (
    echo [INFO] Reloading the project nginx instance...
    "%NGINX_BIN%" -p "%NGINX_PREFIX%/" -c "%NGINX_CONF%" -s reload
    if errorlevel 1 (
        echo [ERROR] nginx reload failed. Check deploy\nginx\logs\error.log.
        exit /b 1
    )
) else (
    echo [INFO] Starting the project nginx instance...
    start "zhiyun-frontend-nginx" /B "%NGINX_BIN%" -p "%NGINX_PREFIX%/" -c "%NGINX_CONF%"
)

for /L %%I in (1,1,20) do (
    curl.exe -fsS "%FRONTEND_URL%/" >nul 2>nul
    if not errorlevel 1 goto :ready
    ping 127.0.0.1 -n 2 >nul
)

echo [ERROR] nginx did not become ready. Check deploy\nginx\logs\error.log.
exit /b 1

:ready
echo [OK] Production frontend is available at %FRONTEND_URL%/
echo [INFO] API proxy: %FRONTEND_URL%/api/ ^> http://127.0.0.1:8081/
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
