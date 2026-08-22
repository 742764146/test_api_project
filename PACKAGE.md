# 压测平台 打包发布手册

> 本手册说明如何把「压测」平台打包成**免安装桌面应用**，双击图标后以**原生窗口**打开（不依赖浏览器），无需单独部署后端、无需预装 Java。

---

## 一、运行原理

- **后端**：`PerfServer.java` 内置 HTTP 服务，监听本机 `127.0.0.1:8080`（单实例）。
- **前端**：`index.html`（作为资源内嵌在 jar 中）。
- **原生窗口**：`AppWindow.java` 用 **JavaFX WebView** 加载本地服务页面，以桌面窗口呈现；`Launcher.java` 负责后台启动服务并打开窗口。

```
双击 压测.app / 压测.exe
        │
        ├── 后台启动内嵌 JVM + HTTP 服务(127.0.0.1:8080)
        └── 打开原生 JavaFX 窗口(WebView 加载本地页面)
```

- 目标机器**不需要装 Java、不需要浏览器、不需要部署**
- 关闭窗口即退出应用；历史记录保存在用户数据目录，升级不丢

---

## 二、前置依赖

| 依赖 | 说明 |
|---|---|
| JDK 21+ | 含 `javac`、`jar`、`jpackage` |
| JavaFX 21 SDK | 脚本自动从 Gluon 下载（缺失时） |
| macOS | 系统自带 `iconutil` |
| Windows | 需另下载 Windows 版 JavaFX（见 6.2） |

---

## 三、目录结构

```
api-perf-test/
├── PerfServer.java      # 后端(单文件,含 startServer)
├── Launcher.java        # 打包入口(启动服务 + 打开窗口)
├── AppWindow.java       # JavaFX 原生窗口
├── index.html           # 前端(内嵌进 jar)
├── IconGen.java         # 图标生成器
├── build.sh             # macOS 打包脚本
├── build.bat            # Windows 打包脚本
├── assets/              # 图标(app.ico / app.icns)
├── javafx-sdk-21.0.4/   # JavaFX 运行时(构建依赖)
├── PACKAGE.md           # 本文档
└── 压测-1.0.0.dmg        # 打包产物(macOS)
```

---

## 四、代码结构说明

1. **PerfServer.startServer(port)**：启动 HTTP 服务的公共方法，供开发模式(`main`)与窗口模式(`Launcher`)共用；端口占用抛 `BindException`。
2. **Launcher.main**：非 `Application` 的启动类，先后台起服务，再 `Application.launch(AppWindow.class)`。必须用独立 Launcher 类，否则类路径下 JavaFX 会报「runtime components missing」。
3. **AppWindow**：JavaFX `WebView` 加载 `http://127.0.0.1:8080/`，窗口标题「压测 v1.0.0」，最小尺寸 800×800，等服务就绪后再加载页面。

---

## 五、图标生成

```bash
java IconGen.java
# 产物: assets/icon.png、icon_16/32/64/128/256/512.png、app.ico
```

macOS `.icns`（build.sh 已内置）：

```bash
rm -rf icon.iconset && mkdir icon.iconset
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
```

---

## 六、打包命令

### 6.1 macOS（产出 `压测-1.0.0.dmg`）

一键脚本（自动下载 JavaFX、编译、打包）：

```bash
./build.sh
```

等价手动命令：

```bash
VERSION="1.0.0"; APP_NAME="压测"; MAIN_CLASS="Launcher"
JAVAFX_DIR="javafx-sdk-21.0.4"

# 下载 JavaFX(缺失时,arm64=aarch64 / x64)
curl -L -s -o javafx-sdk.zip "https://download2.gluonhq.com/openjfx/21.0.4/openjfx-21.0.4_osx-aarch64_bin-sdk.zip"
unzip -q -o javafx-sdk.zip && rm javafx-sdk.zip

# 1) 编译(JavaFX 上类路径)
rm -rf build dist && mkdir -p build/classes dist
javac -encoding UTF-8 -cp "$JAVAFX_DIR/lib/*" -d build/classes \
  PerfServer.java Launcher.java AppWindow.java

# 2) 打 jar(index.html 内嵌)
cp index.html build/classes/index.html
jar cfe dist/perf-test.jar "$MAIN_CLASS" -C build/classes .

# 3) 拷贝 JavaFX 运行库
cp "$JAVAFX_DIR"/lib/*.jar dist/
cp "$JAVAFX_DIR"/lib/*.dylib dist/

# 4) jpackage
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
```

### 6.2 Windows（产出 `压测-1.0.0.exe`）

一键脚本（自动下载 Windows 版 JavaFX、编译、打包，需在 Windows 上执行）：

```bat
build.bat
```

脚本内部逻辑（与 macOS 一致，区别在 JavaFX 为 `windows-x64`、动态库为 `.dll`、图标为 `.ico`）：

```bat
javac -encoding UTF-8 -cp "javafx-sdk-21.0.4\lib\*" -d build\classes PerfServer.java Launcher.java AppWindow.java
copy index.html build\classes\index.html
jar cfe dist\perf-test.jar Launcher -C build\classes .
copy javafx-sdk-21.0.4\lib\*.jar dist\
copy javafx-sdk-21.0.4\lib\*.dll dist\

jpackage --type exe --name 压测 --app-version 1.0.0 ^
  --input dist --main-jar perf-test.jar --main-class Launcher ^
  --icon assets\app.ico --vendor "一叶知秋" --dest . ^
  --win-shortcut --win-menu ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Djava.library.path=$APPDIR"
```

> `.msi` 需 WiX 3.x（`--type msi`）；`.exe` 为自解压安装器，无需额外依赖。

### 6.3 一键构建双端（CI）

jpackage **不能跨平台交叉编译**（macOS 上只能出 .dmg，Windows 上只能出 .exe），所以「一次打包双端」用 GitHub Actions 双平台并行构建完成，已内置在 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

- 触发方式：
  1. 仓库 Actions 页 → **Run workflow** 手动触发
  2. 打 tag（如 `git tag v1.0.0 && git push --tags`）自动触发
- 两个 runner（`macos-latest` + `windows-latest`）**并行**执行 `build.sh` / `build.bat`
- 产物 `压测-1.0.0.dmg`、`压测-1.0.0.exe` 分别上传为 workflow artifact

使用步骤：

```bash
# 本地(任一平台)推到 GitHub
git init
git add .
git commit -m "v1.0.0"
git remote add origin https://github.com/<你的账号>/<仓库>.git
git push -u origin main
git tag v1.0.0
git push --tags   # 触发双端构建
```

构建完成后在 GitHub 仓库的 **Actions → 具体 run → Artifacts** 下载两个安装包。

---

## 七、产物与说明

| 平台 | 产物 | 双击体验 |
|---|---|---|
| macOS | `压测-1.0.0.dmg` | 挂载后拖「压测.app」进应用程序，双击打开原生窗口 |
| Windows | `压测-1.0.0.exe` | 安装后桌面/开始菜单快捷方式，双击打开原生窗口 |

**数据目录（升级不丢）：**

- macOS：`~/Library/Application Support/PerfTest/`
- Windows：`%LOCALAPPDATA%\PerfTest\`

---

## 八、版本升级

1. 修改 `PerfServer.java` 顶部 `VERSION` 常量，同步改 `build.sh` / `build.bat` 的 `VERSION`。
2. 重新打包覆盖安装即可；历史记录在用户数据目录不受影响。

预留 `/api/version` 接口，后续可加 `latest.json` + 前端更新横幅实现自动提示。

---

## 九、常见问题

| 问题 | 处理 |
|---|---|
| macOS 首次打开报「已损坏/无法验证开发者」 | 右键「打开」绕过；或 `xattr -cr 压测.app` |
| Windows SmartScreen 拦截 | 「更多信息 → 仍要运行」；正式签名需购买证书 |
| 启动日志出现「Unsupported JavaFX configuration」 | 类路径模式的无害警告，不影响运行 |
| 双击后窗口没出现 | 手动访问 `http://127.0.0.1:8080` 确认服务是否起来；端口被占用时会复用现有实例 |
| Windows 打包中文乱码 | 打包前 `chcp 65001` |
| 想改默认端口 | 修改 `Launcher.java` / `PerfServer.java` 中的默认端口 8080 |
