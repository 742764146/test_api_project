@echo off
REM ============ Windows 打包脚本(原生窗口版) ============
REM 产出: 压测-1.0.0.exe(双击以原生 JavaFX 窗口打开,自带 JRE)
REM 依赖: JDK 21+(含 jpackage)、网络(首次下载 JavaFX)
chcp 65001 >nul
setlocal
cd /d %~dp0

set VERSION=1.0.0
set APP_NAME=压测
set MAIN_CLASS=Launcher
set JAVAFX_VER=21.0.4
set JAVAFX_DIR=javafx-sdk-%JAVAFX_VER%

REM 0) 下载 JavaFX(缺失时)
if not exist "%JAVAFX_DIR%\lib" (
  echo ==^> 0/5 下载 JavaFX %JAVAFX_VER%
  curl -L -s -o javafx-sdk.zip "https://download2.gluonhq.com/openjfx/%JAVAFX_VER%/openjfx-%JAVAFX_VER%_windows-x64_bin-sdk.zip"
  if errorlevel 1 ( echo JavaFX 下载失败 & exit /b 1 )
  powershell -NoProfile -Command "Expand-Archive -Force javafx-sdk.zip ."
  del /q javafx-sdk.zip 2>nul
)

echo ==^> 1/5 清理与编译
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes dist

javac -encoding UTF-8 -cp "%JAVAFX_DIR%\lib\*" -d build\classes PerfServer.java Launcher.java AppWindow.java
if errorlevel 1 exit /b 1

echo ==^> 2/5 打包 jar(index.html 内嵌)
copy index.html build\classes\index.html >nul
jar cfe dist\perf-test.jar %MAIN_CLASS% -C build\classes .
if errorlevel 1 exit /b 1

echo ==^> 3/5 拷贝 JavaFX 运行库(.jar + .dll)
copy "%JAVAFX_DIR%\lib\*.jar" dist\ >nul
copy "%JAVAFX_DIR%\lib\*.dll" dist\ >nul

echo ==^> 4/5 生成图标(已存在则跳过)
if not exist assets\app.ico (
  java IconGen.java
)

echo ==^> 5/5 jpackage 生成 exe
jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version %VERSION% ^
  --input dist ^
  --main-jar perf-test.jar ^
  --main-class %MAIN_CLASS% ^
  --icon assets\app.ico ^
  --vendor "一叶知秋" ^
  --dest . ^
  --win-shortcut ^
  --win-menu ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Djava.library.path=$APPDIR"

if errorlevel 1 ( echo 打包失败 & exit /b 1 )

echo ==^> 完成: %APP_NAME%-%VERSION%.exe
