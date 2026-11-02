@echo off
title SERVIDOR BIBLIOTECA - NO CERRAR
cd /d "%~dp0"

rem Si el puerto 3000 ya esta ocupado, el servidor ya esta encendido en otra ventana
netstat -ano | findstr ":3000 " | findstr "LISTENING" >nul
if not errorlevel 1 goto ocupado

rem Si existe ngrok.exe y dominio.txt, abre la direccion publica en otra ventana
if not exist "%~dp0ngrok.exe" goto servidor
if not exist "%~dp0dominio.txt" goto servidor
taskkill /F /IM ngrok.exe >nul 2>&1
set /p DOMINIO=<"%~dp0dominio.txt"
echo Direccion publica: https://%DOMINIO%
start "NGROK - NO CERRAR" "%~dp0ngrok.exe" http --url=%DOMINIO% 3000

:servidor
echo Encendiendo el servidor...
node server.js
echo.
netstat -ano | findstr ":3000 " | findstr "LISTENING" >nul
if not errorlevel 1 goto ocupado
echo El servidor se detuvo. Se vuelve a encender en 3 segundos...
timeout /t 3 >nul
goto servidor

:ocupado
echo.
echo  El puerto 3000 ya esta ocupado: hay otro servidor encendido.
echo  Cierra TODAS las ventanas negras y vuelve a abrir iniciar-servidor.
echo  Si sigue pasando, en CMD escribe:  taskkill /F /IM node.exe
echo.
pause
