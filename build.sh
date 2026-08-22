#!/usr/bin/env bash
# ============ macOS 打包脚本(原生窗口版) ============
# 产出: 压测-1.0.0.dmg,双击后以原生 JavaFX 窗口打开(不再依赖浏览器)
# 依赖: JDK 21+(含 jpackage)、系统 iconutil、网络(首次下载 JavaFX)
set -euo pipefail
cd "$(dirname "$0")"

VERSION="1.0.0"
APP_NAME="压测"
MAIN_CLASS="Launcher"
JAVAFX_VER="21.0.4"
JAVAFX_DIR="javafx-sdk-${JAVAFX_VER}"

# 0) 下载 JavaFX(缺失时)
if [ ! -d "$JAVAFX_DIR/lib" ]; then
  ARCH=$(uname -m)
  [ "$ARCH" = "arm64" ] && FXARCH="aarch64" || FXARCH="x64"
  echo "==> 下载 JavaFX ${JAVAFX_VER} (${FXARCH})"
  curl -L -s -o javafx-sdk.zip "https://download2.gluonhq.com/openjfx/${JAVAFX_VER}/openjfx-${JAVAFX_VER}_osx-${FXARCH}_bin-sdk.zip"
  unzip -q -o javafx-sdk.zip
  rm -f javafx-sdk.zip
fi

echo "==> 1/5 清理与编译(含 JavaFX)"
rm -rf build dist
mkdir -p build/classes dist
javac -encoding UTF-8 -cp "$JAVAFX_DIR/lib/*" -d build/classes \
  PerfServer.java Launcher.java AppWindow.java

echo "==> 2/5 打包 jar(index.html 作为资源内嵌)"
cp index.html build/classes/index.html
jar cfe dist/perf-test.jar "$MAIN_CLASS" -C build/classes .

echo "==> 3/5 拷贝 JavaFX 运行库到打包目录"
cp "$JAVAFX_DIR"/lib/*.jar dist/
cp "$JAVAFX_DIR"/lib/*.dylib dist/

echo "==> 4/5 重新生成图标(已存在则跳过)"
if [ ! -f assets/app.icns ]; then
  java IconGen.java
  rm -rf icon.iconset && mkdir -p icon.iconset
  cp assets/icon_16.png  icon.iconset/icon_16x16.png
  cp assets/icon_32.png  icon.iconset/icon_16x16@2x.png
  cp assets/icon_32.png  icon.iconset/icon_32x32.png
  cp assets/icon_64.png  icon.iconset/icon_32x32@2x.png
  cp assets/icon_128.png icon.iconset/icon_128x128.png
  cp assets/icon_256.png icon.iconset/icon_128x128@2x.png
  cp assets/icon_256.png icon.iconset/icon_256x256.png
  cp assets/icon_512.png icon.iconset/icon_256x256@2x.png
  cp assets/icon_512.png icon.iconset/icon_512x512.png
  cp assets/icon.png   icon.iconset/icon_512x512@2x.png
  iconutil -c icns icon.iconset -o assets/app.icns
  rm -rf icon.iconset
fi

echo "==> 5/5 jpackage 生成 .app + .dmg"
jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$VERSION" \
  --input dist \
  --main-jar perf-test.jar \
  --main-class "$MAIN_CLASS" \
  --icon assets/app.icns \
  --vendor "一叶知秋" \
  --dest . \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Djava.library.path=\$APPDIR"

echo "==> 完成: ${APP_NAME}-${VERSION}.dmg(双击以原生窗口打开)"
