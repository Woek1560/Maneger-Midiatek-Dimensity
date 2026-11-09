
@echo off
chcp 65001 >nul
title Woek - GitHub Auto Upload

echo ==========================================
echo       ENVIANDO PROJETO PARA O GITHUB
echo ==========================================
echo.

git init

git status

echo.
echo Adicionando arquivos...
git add .

echo.
echo Criando commit...
git commit -m "commit :tada:"

echo.
echo Configurando branch principal...
git branch -M main

echo.
echo Configurando repositorio remoto...
git remote get-url origin >nul 2>&1
if errorlevel 1 (
    git remote add origin https://github.com/Woek1560/Maneger-Midiatek-Dimensity.git
) else (
    git remote set-url origin https://github.com/Woek1560/Maneger-Midiatek-Dimensity.git
)

echo.
echo Enviando arquivos para o GitHub...
git push -u origin main

echo.
echo ==========================================
echo             PROCESSO FINALIZADO
echo ==========================================
echo.
pause
