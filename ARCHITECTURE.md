# 压测平台 · 架构改造技术方案

> 目标:在不牺牲体验的前提下,**显著缩小安装包体积、提升引擎性能、彻底解决 JavaFX WebView 的能力限制**(本次「导出」反复修不好的根因就在这)。
> 本文档分为两部分:**现有业务流程**(重构要保住的「业务真相」)与**改造方案**(怎么重建)。先看清业务,再谈重构。

---

## 一、现状与问题

### 1.1 当前架构

```
index.html(60KB,原生 JS)
        │  WebView 加载 http://127.0.0.1:8080
        ▼
AppWindow.java(JavaFX WebView 壳,74 行)
        │
Launcher.java(27 行:起服务 + 开窗口)
        │
PerfServer.java(1946 行,单文件 HTTP 后端)
   ├─ 冒烟测试:fork curl 子进程(ProcessBuilder)
   ├─ 采样监测:java.net.http.HttpClient
   └─ 压测:HttpClient + CountDownLatch 并发
```

### 1.2 安装包体积拆解(实测)

| 组成部分 | 大小 | 说明 |
|---|---|---|
| `libjfxwebkit.dylib`(JavaFX 自带 WebKit) | **110 MB** | **体积主因** |
| 其余 JavaFX 原生库(glass/prism/media) | ~3 MB | |
| jlink 裁剪后的 JRE | ~50 MB | java.base 等 |
| **合计 `.dmg`** | **112 MB** | |

结论:体积大**不是业务代码造成的**——前后端源码加起来才约 156KB;而是「JavaFX 自带的 WebKit(110MB)+ JRE(50MB)」造成的。

### 1.3 具体痛点

1. **体积 112MB**:主要来自 JavaFX 自带 WebKit(110MB),而这套 WebKit 仅仅是为了给页面当一层壳。
2. **WebView 能力受限**:不支持 `<a download>`;JS→Java 桥在 jpackage 运行时里方法暴露失效(实测 `save=undefined`);原生 `FileChooser` 弹不出来——本次「导出」功能反复修不好的三个根因都在这。
3. **本地端口冲突**:前后端靠 `127.0.0.1:8080` 通信,一旦旧实例没退出,新实例「复用现有实例」会加载到旧页面,造成数据错乱/功能失效。
4. **不能交叉编译**:jpackage 在 macOS 只能出 .dmg、Windows 只能出 .exe,只能上 CI 双平台各跑一次(见 PACKAGE.md 6.3)。
5. **引擎性能**:冒烟测试每请求 fork 一个 `curl` 子进程(进程开销 + 计时依赖 `-w` 文本解析,精度受限);高并发压测受 JVM 线程模型约束。
6. **可维护性**:后端 1946 行单文件、状态全是 static 字段,难以单测、难以扩展。

---

## 二、现有业务流程(重构要保住的业务真相)

> 这一节是重构的「需求基线」。无论用什么语言/框架重写,**下面的功能、输入输出、判读逻辑都必须保持一致**。

### 2.1 产品定位与整体工作流

「接口性能测试平台」,核心理念:**冒烟定位 → 采样监测(抓偶发慢)→ 阶梯压测**。服务端执行、不受浏览器并发限制;压测记录自动保存、可复用配置。

```
配置接口(最多 10 个,http/https/ws/wss)
        │
  ① 冒烟测试 ── 定位慢在哪一段(DNS/TCP/TLS/首字节/总耗时)
        │ 发现偶发慢 / 需要长观察
  ② 采样监测 ── 定时采样抓慢请求与异常,看是否有周期规律
        │ 定位容量上限
  ③ 阶梯压测 ── 分阶段加压,找容量拐点(并发↑但 QPS 不再↑、延迟陡增)
        │
  导出 CSV / JSON;压测记录自动保存可复用
```

### 2.2 核心数据模型:接口配置(TargetSpec)

每个接口一张卡片,可单独配置:

| 字段 | 说明 |
|---|---|
| 协议 proto | `http` / `https` / `ws` / `wss` |
| 方法 method | `GET` / `POST` / `PUT` / `DELETE`(WS 时置灰) |
| URL | 不含协议,如 `api.example.com/path` |
| 请求参数 query | URL 查询串 |
| Body | 非 GET 请求体(JSON) |
| 请求头 headers | 多行 `Key: Value` |
| WS 消息 wsmsg | 每次循环发送一次,可多行 JSON/文本 |

- 接口上限 **10 个**;多接口同时压测时,**每个接口分配完整并发数**(如 100 并发 × 3 接口 = 总并发 300),用于混合流量模拟。

### 2.3 三大模块业务流

#### ① 冒烟测试(smoke)——「定位」

- **输入**:接口列表、每接口请求次数(1~20,默认 5)、Token、弱网参数。
- **流程**:对每个接口**串行**发 N 次请求,每次间隔 200ms;记录 DNS / TCP 连接 / TLS / 首字节 / 总耗时 / 状态码 / 响应体(截断 ≤2000 字节)。
- **实现(现状)**:每请求 fork `curl` 子进程,用 `-w "%{time_namelookup} %{time_connect} ..."` 解析分阶段耗时。
- **输出**:分接口分阶段耗时表。
- **判读规则**(内置提示):
  - `dns` 高 → DNS 问题;`connect` 高 → 网络链路;`tls` 高 → 握手慢;
  - `firstByte − connect` 高 → **服务端处理慢**;`total − firstByte` 高 → 响应体过大/带宽不足。

#### ② 采样监测(monitor)——「抓偶发慢」

- **输入**:接口列表、时长(默认 600s)、间隔(默认 2s)、慢阈值(默认 500ms)、Token、弱网参数。
- **流程**:每间隔对所有接口各发 1 次请求,持续到时长为结束;记录每次延迟、**慢请求(> 阈值)**、异常、请求/响应流水(最多 200 条)。
- **实现(现状)**:`HttpClient` + 定时循环。
- **输出**:
  - 响应延迟走势图(红线为慢阈值);
  - 分接口统计(采样数 / 分位数);
  - 慢请求 / 异常记录(最新 50 条,观察时间戳周期规律);
  - 请求 / 响应明细(可展开看请求头/请求体/响应体)。

#### ③ 阶梯压测(load)——「找容量拐点」

- **输入**:接口列表、阶段配置(每阶段「时长秒 并发」逗号分隔,默认 5 段:`60 5,180 20,180 50,180 100,180 200`)、Token、弱网参数。
- **流程**:按阶段逐段加压;每接口分配完整并发;统计 QPS / 延迟分位数(P50/P90/P95/P99)/ 错误率 / 错误分布。
- **实现(现状)**:`HttpClient` + `CountDownLatch` 控制并发。
- **输出**:
  - 每秒 QPS(柱)与平均延迟 ms(线)图、延迟分位数图;
  - 分接口实时统计、阶段结果全局汇总;
  - 错误分布、请求/响应采样明细。
- **判读规则**:阶梯找容量拐点(并发上升但 QPS 不再增长、延迟陡增处即为拐点),**生产容量建议控制在拐点 70% 以内**。

### 2.4 跨模块能力

| 能力 | 说明 |
|---|---|
| 全局 Token | 每模块独立,自动存 localStorage;支持「Token 值」或「X-Token: xxx」形式 |
| 弱网模拟 | 延迟 ms / 抖动 ms / 下行 KB/s / 上行 KB/s;内置 正常 / 4G / 3G / 弱3G 预设 |
| 接口历史 | 最近用过的接口自动记住,下拉复用 / 清空 |
| 压测记录 | 每次压测自动保存,可查看 / 复用配置 / 删除 |
| 导出 | 冒烟 / 监测 / 压测各可导出 CSV / JSON |

### 2.5 后端 API 一览(现状)

| 方法/路径 | 作用 |
|---|---|
| `GET /` | 静态页 |
| `GET /api/version` | 版本信息 |
| `GET /api/interfaces` / `GET /api/interfaces/clear` | 接口历史查询 / 清空 |
| `POST /api/netcheck` | 发起前网络连通探测 |
| `POST /api/smoke` | 运行冒烟测试 |
| `GET /api/smoke/export` | 冒烟结果(原样内容) |
| `POST /api/monitor/start` / `stop`,`GET /api/monitor/status` | 监测控制 + 状态 |
| `POST /api/load/start` / `stop`,`GET /api/load/status` | 压测控制 + 状态 |
| `GET /api/monitor/export` / `GET /api/load/export` | 监测/压测结果(原样内容) |
| `GET /api/export` | **新增**:写文件到 Downloads 并返回路径(用于原生窗口导出) |
| `GET /api/history/list` / `get` / `delete` | 压测记录管理 |

### 2.6 存储与状态

- **数据目录**:macOS `~/Library/Application Support/PerfTest`;Windows `%LOCALAPPDATA%\PerfTest`。
- **压测历史**:`<数据目录>/history/`。
- **接口历史**:内存(会话内)记住最近接口。
- **前端 Token**:浏览器 `localStorage`。
- **任务生命周期**:每模块「空闲 → 运行中 → 完成/错误」;监测/压测同时只允许一个任务。

---

## 三、改造目标(可量化)

| 维度 | 现状 | 目标 |
|---|---|---|
| 安装包体积 | 112 MB | **< 30 MB**(理想 < 15 MB) |
| 原生能力 | 文件对话框失效、无下载 | 原生对话框 / 菜单 / 托盘可用 |
| 前后端通信 | 本地 HTTP + 端口冲突 | **IPC,无端口** |
| 引擎并发 | JVM 线程 + curl 子进程 | 原生协程 + 精确计时 |
| 交叉编译 | 不支持 | 支持 |
| 可测试性 | 单文件、难单测 | 引擎独立、可单测 |

---

## 四、候选方案对比

| 方案 | 语言 | 体积 | 原生体验 | 引擎适配 | 学习/维护成本 | 结论 |
|---|---|---|---|---|---|---|
| JavaFX + jpackage(现状) | Java | 112 MB | 一般(对话框失效) | JVM | 低 | 体积/能力硬伤 |
| Electron | JS/TS | 100~150 MB | 好 | Node(不擅长高并发压测) | 低 | 体积无改善 |
| Flutter Desktop | Dart | 30~50 MB | 好 | Dart(引擎语言错配) | 中 | 引擎错配 |
| **Tauri** | **Rust** + 前端 | **5~15 MB** | 极好 | Rust(reqwest/tokio)极强 | 中高 | 最小、最安全 |
| **Wails** | **Go** + 前端 | **15~30 MB** | 好 | Go(net/http)极强 | 中 | 单语言、最贴合压测 |

要点:

- **Electron** 打包整个 Chromium,体积和现在差不多,换汤不换药,不推荐。
- **Tauri / Wails 都用「系统 WebView」**(macOS 用 WKWebView、Windows 用 WebView2),WebKit 由操作系统提供、**不打包**——这正是砍掉当前 110MB 的来源。
- **压测引擎**本质是「高并发 HTTP + 精确计时」,Go/Rust 的协程 + 原生 HTTP 客户端天生契合(k6 就是 Go 写的)。

---

## 五、推荐方案:Wails(Go)为主,Tauri(Rust)为备选

> 理由:压测工具的核心是引擎,Go 是这类工具的事实标准语言;Wails 用 Go **同时写引擎和壳**——单一语言、无 Node 构建步骤也能直接复用现有 `index.html`。若追求极致体积与安全性,改选 Tauri(Rust)。

### 5.1 目标架构

```
index.html(复用现有 UI,可选迁移 Vite + TS)
        │  IPC(runtime.Events / 直接调用 Go 方法)
        ▼
Wails 壳(Go,系统 WKWebView / WebView2)
        │
Go 引擎(goroutine 并发)
   ├─ 冒烟 / 监测 / 压测:net/http + httptrace 计时
   ├─ WebSocket:gorilla/websocket
   └─ 弱网限速:自实现限速 reader(替代 curl --limit-rate)
```

### 5.2 引擎设计(Go 版要点)

- **HTTP 请求**:`http.Client` + 自定义 `http.Transport`(连接池、keep-alive、单主机最大连接数)。
- **分阶段计时**:用 `httptrace.ClientTrace` 拿到 DNS / TCP 连接 / TLS / 首字节耗时,等价于现在 curl `-w` 的输出,但**无子进程、精度更高**:

```go
var dns, conn, tls, firstByte time.Time
trace := &httptrace.ClientTrace{
    DNSStart:     func(httptrace.DNSStartInfo) { dns = time.Now() },
    DNSDone:      func(httptrace.DNSDoneInfo)   { row.DNS = time.Since(dns) },
    ConnectStart: func(_, _ string)             { conn = time.Now() },
    ConnectDone:  func(_, _ string, err error)  { row.Connect = time.Since(conn) },
    TLSHandshakeDone: func(_, _ string, _ error){ row.TLS = time.Since(tls) },
    GotFirstResponseByte: func()                { row.FirstByte = time.Since(firstByte) },
}
req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))
```

- **并发模型**:worker pool + `sync.WaitGroup` 控制并发数(对应现有 `CountDownLatch`),比 JVM 线程更轻、可跑到更高并发。
- **WebSocket**:`github.com/gorilla/websocket` 或 `nhooyr.io/websocket`。
- **弱网限速**:包一层限速 `io.Reader` 实现下行限速,替代 `curl --limit-rate`;上行/延迟抖动在引擎层注入。

### 5.3 前端改造

- **复用现有 `index.html`** 的 UI 与逻辑(998 行几乎不动)。
- 把 `fetch('/api/...')` 换成 Wails 绑定 `window.go.main.App.Smoke(...)` / `MonitorStart(...)` / `LoadStart(...)`。
- **进度推送**:监测/压测的实时进度,用 `runtime.EventsEmit` 从 Go 推给前端订阅,替代现在的轮询 `/api/*/status`。
- **导出**:改用系统**原生保存对话框**(Wails/Tauri 都提供,不会像 JavaFX 那样失效),或直接写 Downloads + 系统通知。

### 5.4 打包与分发

- `wails build` 直接出 `.app` / `.exe`(15~30 MB),支持交叉编译(macOS 上可出 Windows 包)。
- 配自动更新(Wails/Tauri 都有内置 update 机制)。
- 数据目录沿用系统标准路径(Go 里 `os.UserConfigDir()` 等),升级不丢历史记录。

---

## 六、分阶段落地路线

| 阶段 | 内容 | 产出 | 说明 |
|---|---|---|---|
| 1 | Go 引擎重写 | 可独立运行的引擎 + 单测 | 先脱离 UI,做到与 curl 等价功能 |
| 2 | 接 Wails 壳 | 系统 WebView 窗口 | 复用 index.html,通信换 IPC |
| 3 | 前端现代化(可选) | Vite + TS + 组件化 | 提升可维护性,非必须 |
| 4 | 打包 + 自动更新 | .dmg / .exe | 交叉编译、代码签名 |

建议顺序:**阶段 1 先落地引擎**(价值最大、风险最低,可并行于现有 Java 版),验证性能达标后再做阶段 2。

---

## 七、风险与取舍

- **迁移成本**:需重写 1946 行后端;可逐步搬(先冒烟 → 再监测 → 再压测),每步都能独立验证。
- **系统 WebView 差异**:macOS WKWebView / Windows WebView2 行为略有差异,但比 JavaFX 自带的 WebKit 更标准、更受主流支持。
- **语言选型**:若团队对 Go 不熟、又更看重极致体积与内存安全,改选 **Tauri(Rust)**;其余架构思路完全一致。

---

## 附:与本次「导出」修复的关系

本次导出问题最终是靠「走后端 HTTP + 页面 toast」绕开的(症状级修复)。根因是 JavaFX WebView 的三个硬伤(无下载、桥失效、原生对话框失效)+ 旧进程占端口。**按本方案改造后,这些根因会一并消失**:IPC 无端口、系统 WebView 无桥问题、原生对话框可用、体积从 112MB 降到 30MB 以内。
