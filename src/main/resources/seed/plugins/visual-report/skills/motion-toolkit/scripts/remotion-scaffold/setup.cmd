@echo off
rem motion-toolkit · Remotion 脚手架安装（npmmirror 镜像，registry.npmjs.org 直连会 stall）
cd /d "%~dp0"
npm install --registry https://registry.npmmirror.com
