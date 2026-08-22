# ============ Windows 打包脚本(PowerShell,正确处理 UTF-8 中文应用名) ============
# 产出: 压测-1.0.0.exe(双击以原生 JavaFX 窗口打开,自带 JRE)
# 依赖: JDK 21+(含 jpackage)、网络(首次下载 JavaFX)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$VERSION = "1.0.0"
$APP_NAME = "压测"
$MAIN_CLASS = "Launcher"
$JAVAFX_VER = "21.0.4"
$JAVAFX_DIR = "javafx-sdk-$JAVAFX_VER"

# 0) 下载 JavaFX(缺失时)
if (-not (Test-Path "$JAVAFX_DIR\lib")) {
  Write-Host "==> 0/5 下载 JavaFX $JAVAFX_VER"
  Invoke-WebRequest -Uri "https://download2.gluonhq.com/openjfx/$JAVAFX_VER/openjfx-${JAVAFX_VER}_windows-x64_bin-sdk.zip" -OutFile "javafx-sdk.zip"
  Expand-Archive -Force "javafx-sdk.zip" -DestinationPath .
  Remove-Item "javafx-sdk.zip"
}

# 1) 清理与编译
Write-Host "==> 1/5 清理与编译"
if (Test-Path build) { Remove-Item -Recurse -Force build }
if (Test-Path dist) { Remove-Item -Recurse -Force dist }
New-Item -ItemType Directory -Force -Path build\classes | Out-Null
New-Item -ItemType Directory -Force -Path dist | Out-Null

javac -encoding UTF-8 -cp "$JAVAFX_DIR\lib\*" -d build\classes PerfServer.java Launcher.java AppWindow.java
if ($LASTEXITCODE -ne 0) { Write-Host "编译失败"; exit 1 }

# 2) 打 jar(index.html 内嵌)
Write-Host "==> 2/5 打包 jar"
Copy-Item index.html build\classes\index.html
jar cfe dist\perf-test.jar $MAIN_CLASS -C build\classes .
if ($LASTEXITCODE -ne 0) { Write-Host "打 jar 失败"; exit 1 }

# 3) 拷贝 JavaFX 运行库(.jar 在 lib/,.dll 在 bin/)
Write-Host "==> 3/5 拷贝 JavaFX 运行库"
Copy-Item "$JAVAFX_DIR\lib\*.jar" dist\
Copy-Item "$JAVAFX_DIR\bin\*.dll" dist\

# 4) 生成图标(已存在则跳过)
Write-Host "==> 4/5 生成图标"
if (-not (Test-Path assets\app.ico)) { java IconGen.java }

# 5) jpackage 生成 exe
Write-Host "==> 5/5 jpackage 生成 exe"
$jpackageArgs = @(
  '--type', 'exe',
  '--name', $APP_NAME,
  '--app-version', $VERSION,
  '--input', 'dist',
  '--main-jar', 'perf-test.jar',
  '--main-class', $MAIN_CLASS,
  '--icon', 'assets\app.ico',
  '--vendor', '一叶知秋',
  '--dest', '.',
  '--win-shortcut',
  '--win-menu',
  '--java-options', '-Dfile.encoding=UTF-8',
  '--java-options', '-Djava.library.path=$APPDIR'
)
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { Write-Host "打包失败"; exit 1 }

Write-Host "==> 完成: ${APP_NAME}-${VERSION}.exe"
