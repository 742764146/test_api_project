// 后端接口性能测试平台 - 单文件服务
// 运行: java PerfServer.java [端口,默认8080]
// 需要 JDK 21+(虚拟线程);冒烟测试依赖本机 curl
// 仅绑定 127.0.0.1,供本机浏览器使用,请勿暴露到公网
// 压测记录自动保存到 ./history/ 目录

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class PerfServer {

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final int MAX_URLS = 10; // 多接口并行的上限
    static final String VERSION = "1.0.0";
    static final String DEVELOPER = "一叶知秋";
    // 历史调用接口(去重,供快速选择)
    static final List<String[]> interfaceHistory = new ArrayList<>(); // {url, proto, method}
    static final Set<String> interfaceKeys = new HashSet<>();
    static boolean interfacesLoaded = false;

    static HttpServer server; // 保持引用,防止被 GC

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        try {
            startServer(port);
            System.out.println("性能测试平台 v" + VERSION + " 已启动: http://127.0.0.1:" + port + "  (Ctrl+C 停止)");
            openBrowser(port);
        } catch (java.net.BindException e) {
            openBrowser(port);
            System.out.println("检测到应用已在运行,已打开浏览器: http://127.0.0.1:" + port);
        }
    }

    /** 启动 HTTP 服务(供开发模式与窗口模式共用),端口被占用抛 BindException,返回后服务已就绪 */
    public static void startServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", PerfServer::handleStatic);
        server.createContext("/api/version", PerfServer::handleVersion);
        server.createContext("/api/interfaces", PerfServer::handleInterfaceHistory);
        server.createContext("/api/interfaces/clear", PerfServer::handleInterfaceClear);
        server.createContext("/api/netcheck", PerfServer::handleNetcheck);
        server.createContext("/api/smoke", PerfServer::handleSmoke);
        server.createContext("/api/smoke/export", PerfServer::handleSmokeExport);
        server.createContext("/api/monitor/start", PerfServer::handleMonitorStart);
        server.createContext("/api/monitor/stop", PerfServer::handleMonitorStop);
        server.createContext("/api/monitor/status", PerfServer::handleMonitorStatus);
        server.createContext("/api/load/start", PerfServer::handleLoadStart);
        server.createContext("/api/load/stop", PerfServer::handleLoadStop);
        server.createContext("/api/load/status", PerfServer::handleLoadStatus);
        server.createContext("/api/monitor/export", PerfServer::handleMonitorExport);
        server.createContext("/api/load/export", PerfServer::handleLoadExport);
        server.createContext("/api/history/list", PerfServer::handleHistoryList);
        server.createContext("/api/history/get", PerfServer::handleHistoryGet);
        server.createContext("/api/history/delete", PerfServer::handleHistoryDelete);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** 打开默认浏览器访问本机应用 */
    static void openBrowser(int port) {
        String url = "http://127.0.0.1:" + port + "/";
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            System.out.println("自动打开浏览器失败,请手动访问: " + url);
        }
    }

    static void handleVersion(HttpExchange ex) throws IOException {
        respond(ex, 200, "application/json; charset=utf-8",
                "{\"version\":\"" + VERSION + "\",\"developer\":\"" + DEVELOPER + "\"}");
    }

    /** 网络连通性探测:对目标接口做 TCP 连接,任一可达即视为有网络 */
    static void handleNetcheck(HttpExchange ex) throws IOException {
        List<TargetSpec> specs;
        try {
            specs = parseTargets(form(ex));
        } catch (IllegalArgumentException e) {
            respond(ex, 200, "application/json; charset=utf-8", "{\"online\":true,\"total\":0}");
            return;
        }
        if (specs.isEmpty()) {
            respond(ex, 200, "application/json; charset=utf-8", "{\"online\":true,\"total\":0}");
            return;
        }
        List<String> fail = new ArrayList<>();
        for (TargetSpec s : specs) {
            boolean ok = false;
            try {
                URI u = s.fullUri();
                int port = u.getPort() > 0 ? u.getPort()
                        : ("https".equals(u.getScheme()) || "wss".equals(u.getScheme()) ? 443 : 80);
                ok = probe(u.getHost(), port, 2000);
            } catch (Exception ignored) { }
            if (ok) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"online\":true,\"total\":" + specs.size() + "}");
                return;
            }
            fail.add(s.url);
        }
        StringBuilder fb = new StringBuilder("[");
        for (int i = 0; i < fail.size(); i++) {
            if (i > 0) fb.append(",");
            fb.append(json(fail.get(i)));
        }
        fb.append("]");
        respond(ex, 200, "application/json; charset=utf-8",
                "{\"online\":false,\"total\":" + specs.size() + ",\"fail\":" + fb + "}");
    }

    static boolean probe(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用户数据目录(打包后仍可写,升级不丢失) */
    static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base;
        if (os.contains("mac")) {
            base = System.getProperty("user.home") + "/Library/Application Support/PerfTest";
        } else if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            base = (appData != null && !appData.isBlank() ? appData : System.getProperty("user.home"))
                    + File.separator + "PerfTest";
        } else {
            base = System.getProperty("user.home") + "/.perf-test";
        }
        Path p = Path.of(base);
        try { Files.createDirectories(p); } catch (IOException ignored) { }
        return p;
    }

    // ==================== 静态页面 ====================

    static void handleStatic(HttpExchange ex) throws IOException {
        // 优先从 jar 内嵌资源读取(打包后),回退到源码目录文件(开发模式)
        try (InputStream in = PerfServer.class.getResourceAsStream("/index.html")) {
            if (in != null) {
                respond(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return;
            }
        }
        Path file = Path.of(System.getProperty("user.dir"), "index.html");
        if (Files.exists(file)) {
            respond(ex, 200, "text/html; charset=utf-8", Files.readString(file));
            return;
        }
        respond(ex, 500, "text/plain; charset=utf-8", "找不到 index.html,请在 api-perf-test 目录下运行");
    }

    // ==================== 接口配置(TargetSpec:每个接口独立的方法/Body/请求头) ====================

    static class TargetSpec {
        final String url, method, body, query, proto; // proto: http/https/ws/wss
        final String wsMessage; // ws/wss 模式下每次循环发送的消息内容
        final List<String[]> headers; // {name, value}

        TargetSpec(String url, String method, String body, String query,
                   String proto, String wsMessage, List<String[]> headers) {
            this.url = url; this.method = method; this.body = body;
            this.query = query == null ? "" : query.trim();
            this.proto = (proto == null || proto.isBlank()) ? inferProto(url) : proto.trim().toLowerCase();
            this.wsMessage = wsMessage == null ? "" : wsMessage;
            this.headers = headers;
        }

        boolean ws() { return "ws".equals(proto) || "wss".equals(proto); }

        /** 按 proto 规范 scheme 后的最终 URI(含 query) */
        URI fullUri() {
            String u = normalizeUrl(proto, url);
            if (!query.isEmpty()) u += (u.contains("?") ? "&" : "?") + query;
            return URI.create(u);
        }

        static String inferProto(String url) {
            if (url.startsWith("wss://")) return "wss";
            if (url.startsWith("ws://")) return "ws";
            if (url.startsWith("https://")) return "https";
            return "http";
        }

        /** 去掉已有 scheme 后按 proto 拼接,得到规范 URL */
        static String normalizeUrl(String proto, String url) {
            String u = url.trim();
            for (String p : List.of("http", "https", "ws", "wss")) {
                if (u.startsWith(p + "://")) { u = u.substring(p.length() + 3); break; }
            }
            return proto + "://" + u;
        }
    }

    /**
     * 解析接口配置。优先读取逐接口参数 t0.url/t0.method/t0.body/t0.query/t0.headers, t1..., 兼容旧版 urls(按行)+全局 method/body。
     * headers 格式:每行一个 "Key: Value",格式或字符非法时抛出 IllegalArgumentException。
     */
    static List<TargetSpec> parseTargets(Map<String, String> p) {
        List<TargetSpec> list = new ArrayList<>();
        for (int i = 0; i < MAX_URLS; i++) {
            String url = p.get("t" + i + ".url");
            if (url == null || url.isBlank()) break;
            String method = normalizeMethod(p.getOrDefault("t" + i + ".method", "GET"));
            String body = p.getOrDefault("t" + i + ".body", "").trim();
            String query = p.getOrDefault("t" + i + ".query", "").trim();
            String proto = p.getOrDefault("t" + i + ".proto", "");
            String wsmsg = p.getOrDefault("t" + i + ".wsmsg", "");
            List<String[]> headers = parseHeaders(p.getOrDefault("t" + i + ".headers", ""));
            list.add(new TargetSpec(url.trim(), method, body, query, proto, wsmsg, headers));
        }
        if (!list.isEmpty()) return list;
        // 兼容旧参数
        List<String> urls = parseUrls(p);
        String method = normalizeMethod(p.getOrDefault("method", "GET"));
        String body = p.getOrDefault("body", "").trim();
        for (String u : urls) list.add(new TargetSpec(u, method, body, "", "", "", List.of()));
        return list;
    }

    static List<String[]> parseHeaders(String text) {
        List<String[]> hs = new ArrayList<>();
        if (text == null || text.isBlank()) return hs;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int i = line.indexOf(':');
            if (i <= 0) throw new IllegalArgumentException("请求头格式应为 Key: Value,错误行: " + line);
            String name = line.substring(0, i).trim();
            String value = line.substring(i + 1).trim();
            try {
                HttpRequest.newBuilder(URI.create("http://localhost/")).header(name, value).build();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("请求头非法(名称/值含中文或特殊字符): " + line);
            }
            hs.add(new String[]{name, value});
        }
        return hs;
    }

    static String validateSpecs(List<TargetSpec> specs) {
        if (specs.isEmpty()) return "至少提供一个接口 URL";
        if (specs.size() > MAX_URLS) return "最多同时测试 " + MAX_URLS + " 个接口";
        for (TargetSpec s : specs) {
            try {
                s.fullUri();
            } catch (Exception e) {
                return "URL 无效(host + 协议组合后不是合法地址): " + s.url;
            }
        }
        return null;
    }

    /** 按接口配置 + 全局 token 构建请求;upBps>0 时对 POST body 上行限速 */
    static HttpRequest buildRequest(TargetSpec spec, String[] authHeader, long upBps) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(spec.fullUri()).timeout(Duration.ofSeconds(30));
        if (authHeader != null) rb.header(authHeader[0], authHeader[1]);
        for (String[] h : spec.headers) rb.header(h[0], h[1]);
        if ("GET".equals(spec.method)) {
            rb.GET();
        } else { // POST / PUT / DELETE
            HttpRequest.BodyPublisher pub;
            if (spec.body == null || spec.body.isEmpty()) {
                pub = HttpRequest.BodyPublishers.noBody();
            } else {
                rb.header("Content-Type", "application/json");
                pub = HttpRequest.BodyPublishers.ofString(spec.body);
                if (upBps > 0) pub = new ThrottledPublisher(pub, upBps);
            }
            rb.method(spec.method, pub);
        }
        return rb.build();
    }

    static String normalizeMethod(String m) {
        m = m == null ? "" : m.trim().toUpperCase();
        return switch (m) { case "POST", "PUT", "DELETE" -> m; default -> "GET"; };
    }

    // ==================== WebSocket 支持 ====================

    /** 消息收发日志(环形,最多保留 100 条): {方向 S=发送/R=接收, 时间, 内容} */
    static class MsgLog {
        final ArrayDeque<String[]> q = new ArrayDeque<>();

        synchronized void add(String dir, String content) {
            if (q.size() >= 100) q.pollFirst();
            q.addLast(new String[]{dir, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                    truncate(content == null ? "" : content.replace("\n", "\\n"), 300)});
        }

        synchronized String json() {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String[] m : q) {
                if (!first) sb.append(",");
                sb.append("[").append(PerfServer.json(m[0])).append(",")
                  .append(PerfServer.json(m[1])).append(",")
                  .append(PerfServer.json(m[2])).append("]");
                first = false;
            }
            return sb.append("]").toString();
        }
    }

    /** 请求/响应流水(环形): {时间, 方法, URL, 状态码, 耗时ms, 请求头, 请求体, 响应体} */
    static class ReqLog {
        final ArrayDeque<String[]> q = new ArrayDeque<>();
        final int cap;

        ReqLog(int cap) { this.cap = cap; }

        synchronized void add(String time, String method, String url, int status, long ms,
                              String reqHeaders, String reqBody, String respBody) {
            if (q.size() >= cap) q.pollFirst();
            q.addLast(new String[]{time, method, url, String.valueOf(status), String.valueOf(ms),
                    reqHeaders == null ? "" : reqHeaders, reqBody == null ? "" : reqBody, respBody == null ? "" : respBody});
        }

        synchronized String json() {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String[] e : q) {
                if (!first) sb.append(",");
                sb.append("{").append(PerfServer.json("time", e[0])).append(",")
                  .append(PerfServer.json("method", e[1])).append(",")
                  .append(PerfServer.json("url", e[2])).append(",")
                  .append(PerfServer.json("status", e[3])).append(",")
                  .append(PerfServer.json("ms", e[4])).append(",")
                  .append(PerfServer.json("reqHeaders", e[5])).append(",")
                  .append(PerfServer.json("reqBody", e[6])).append(",")
                  .append(PerfServer.json("respBody", truncate(e[7], 2000))).append("}");
                first = false;
            }
            return sb.append("]").toString();
        }
    }

    /** WebSocket 单连接客户端:发送消息并等待下一条回复,计算往返耗时 */
    static class WsClient {
        final WebSocket ws;
        volatile CompletableFuture<String> pending = new CompletableFuture<>();

        WsClient(HttpClient client, URI uri) {
            this.ws = client.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
                final StringBuilder buf = new StringBuilder();

                @Override public CompletableFuture<?> onText(WebSocket w, CharSequence data, boolean last) {
                    buf.append(data);
                    if (last) {
                        String msg = buf.toString();
                        buf.setLength(0);
                        CompletableFuture<String> p = pending;
                        if (p != null && !p.isDone()) p.complete(msg);
                    }
                    w.request(1);
                    return null;
                }

                @Override public void onError(WebSocket w, Throwable error) {
                    CompletableFuture<String> p = pending;
                    if (p != null && !p.isDone()) p.completeExceptionally(error);
                }
            }).join();
        }

        /** 发送消息并等待回复(30s 超时),返回 {往返ms, 回复内容};异常时抛出 */
        String[] roundTrip(String message) throws Exception {
            CompletableFuture<String> p = new CompletableFuture<>();
            pending = p;
            long t0 = System.currentTimeMillis();
            ws.sendText(message, true).join();
            String reply = p.get(30, TimeUnit.SECONDS);
            return new String[]{String.valueOf(System.currentTimeMillis() - t0), reply};
        }

        void closeQuietly() {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { }
            try { ws.abort(); } catch (Exception ignored) { }
        }
    }

    // ==================== 冒烟测试(多接口,curl 获取分阶段耗时) ====================

    static class SmokeRow {
        long seq, dns, connect, tls, firstByte, total, code;
        String error;
        String respBody;
    }

    static class SmokeResult {
        String url, proto, method, reqHeaders, body;
        final List<SmokeRow> rows = new ArrayList<>();
    }

    static volatile List<SmokeResult> lastSmoke;

    static void handleSmoke(HttpExchange ex) throws IOException {
        try {
            smokeInternal(ex);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            respond(ex, 500, "text/plain; charset=utf-8", String.valueOf(e));
        }
    }

    static void smokeInternal(HttpExchange ex) throws Exception {
        Map<String, String> p = form(ex);
        List<TargetSpec> specs;
        try {
            specs = parseTargets(p);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "text/plain; charset=utf-8", e.getMessage());
            return;
        }
        String verr = validateSpecs(specs);
        if (verr != null) { respond(ex, 400, "text/plain; charset=utf-8", verr); return; }
        for (TargetSpec s : specs) {
            if (s.ws()) { respond(ex, 400, "text/plain; charset=utf-8", "冒烟测试不支持 ws/wss 协议,请改用 http/https"); return; }
        }
        int count = clamp(intOr(p.get("count"), 5), 1, 20);
        String token = p.getOrDefault("token", "").trim();
        String tokenName = p.getOrDefault("tokenName", "authorization");
        int rate = clamp(intOr(p.get("rate"), 0), 0, 100000);
        String[] tokenHeader = headerFromToken(token, tokenName);

        List<SmokeResult> results = new ArrayList<>();
        for (TargetSpec s : specs) {
            SmokeResult sr = new SmokeResult();
            sr.url = s.url;
            sr.proto = s.proto;
            sr.method = s.method;
            sr.body = s.body;
            StringBuilder hb = new StringBuilder();
            if (tokenHeader != null) hb.append(tokenHeader[0]).append(": ").append(tokenHeader[1]).append("\n");
            for (String[] h : s.headers) hb.append(h[0]).append(": ").append(h[1]).append("\n");
            sr.reqHeaders = hb.toString();
            String full = s.fullUri().toString();
            for (int i = 1; i <= count; i++) {
                sr.rows.add(runCurl(full, s.method, s.body, tokenHeader, s.headers, rate, i));
                Thread.sleep(200);
            }
            results.add(sr);
        }
        lastSmoke = results;
        rememberInterfaces(specs);

        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < results.size(); r++) {
            SmokeResult sr = results.get(r);
            if (r > 0) sb.append(",");
            sb.append("{").append(json("url", sr.url)).append(",")
              .append(json("proto", sr.proto)).append(",")
              .append(json("method", sr.method)).append(",")
              .append(json("reqHeaders", sr.reqHeaders)).append(",")
              .append(json("reqBody", sr.body)).append(",\"rows\":[");
            for (int i = 0; i < sr.rows.size(); i++) {
                SmokeRow row = sr.rows.get(i);
                if (i > 0) sb.append(",");
                if (row.error != null) {
                    sb.append("{").append(json("seq", row.seq)).append(",").append(json("error", row.error)).append("}");
                } else {
                    sb.append("{").append(json("seq", row.seq))
                      .append(",").append(json("dns", row.dns))
                      .append(",").append(json("connect", row.connect))
                      .append(",").append(json("tls", row.tls))
                      .append(",").append(json("firstByte", row.firstByte))
                      .append(",").append(json("total", row.total))
                      .append(",").append(json("code", row.code))
                      .append(",").append(json("respBody", truncate(row.respBody, 2000))).append("}");
                }
            }
            sb.append("]}");
        }
        sb.append("]");
        respond(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    static SmokeRow runCurl(String url, String method, String body, String[] tokenHeader, List<String[]> headers, int rate, int seq) throws Exception {
        SmokeRow row = new SmokeRow();
        row.seq = seq;
        Path bodyFile = Files.createTempFile("perf-smoke", ".body");
        List<String> cmd = new ArrayList<>(List.of("curl", "-o", bodyFile.toString(), "-sS", "--compressed",
                "--max-time", "30",
                "-w", "%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total} %{http_code}"));
        if (tokenHeader != null) { cmd.add("-H"); cmd.add(tokenHeader[0] + ": " + tokenHeader[1]); }
        for (String[] h : headers) { cmd.add("-H"); cmd.add(h[0] + ": " + h[1]); }
        if (!"GET".equals(method)) { cmd.add("-X"); cmd.add(method); }
        if (!"GET".equals(method) && body != null && !body.isEmpty()) {
            cmd.add("-H"); cmd.add("Content-Type: application/json");
            cmd.add("--data"); cmd.add(body);
        }
        if (rate > 0) { cmd.add("--limit-rate"); cmd.add(rate + "K"); }
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        proc.waitFor();

        // 读取响应体(最多保留 2000 字节)
        try {
            byte[] bb = Files.readAllBytes(bodyFile);
            row.respBody = new String(bb, 0, Math.min(bb.length, 2000), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        Files.deleteIfExists(bodyFile);

        String line = out.contains("\n") ? out.substring(out.lastIndexOf('\n') + 1).trim() : out;
        String[] f = line.split("\\s+");
        if (f.length >= 6) {
            row.dns = ms(f[0]);
            row.connect = ms(f[1]);
            row.tls = ms(f[2]);
            row.firstByte = ms(f[3]);
            row.total = ms(f[4]);
            row.code = (long) Double.parseDouble(f[5]);
        } else {
            row.error = err.isEmpty() ? line : err;
        }
        return row;
    }

    static long ms(String seconds) {
        return Math.round(Double.parseDouble(seconds) * 1000);
    }

    // ==================== 采样监测(多接口并行,抓间歇性慢) ====================

    static volatile Monitor monitor; // 同时只允许一个任务(内含多个接口)

    static class MonTarget {
        final TargetSpec spec;
        final HttpRequest request;
        final MsgLog msgLog = new MsgLog(); // ws 消息收发记录
        final ReqLog reqLog = new ReqLog(200); // http 请求/响应流水
        int total, slowCount, errCount;
        final List<long[]> samples = new CopyOnWriteArrayList<>(); // {epochMs, latencyMs}

        MonTarget(TargetSpec spec, HttpRequest request) { this.spec = spec; this.request = request; }
    }

    static class Monitor {
        volatile boolean running = true;
        final List<MonTarget> targets;
        final String[] authHeader; // {name, value} 或 null
        final int[] net; // 弱网配置
        final int intervalMs, thresholdMs;
        final long endAtMs;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        final List<String[]> slow = new CopyOnWriteArrayList<>(); // {时间, url, 耗时, 状态}

        Monitor(List<TargetSpec> specs, String token, String tokenName, int[] net, int durationSec, int intervalSec, int thresholdMs) {
            this.authHeader = headerFromToken(token, tokenName);
            List<MonTarget> list = new ArrayList<>(specs.size());
            for (TargetSpec s : specs) list.add(new MonTarget(s, s.ws() ? null : buildRequest(s, authHeader, 0)));
            this.targets = list;
            this.net = net;
            this.intervalMs = intervalSec * 1000;
            this.thresholdMs = thresholdMs;
            this.endAtMs = System.currentTimeMillis() + durationSec * 1000L;
        }

        String reqHeaders(MonTarget t) {
            StringBuilder sb = new StringBuilder();
            if (authHeader != null) sb.append(authHeader[0]).append(": ").append(authHeader[1]).append("\n");
            for (String[] h : t.spec.headers) sb.append(h[0]).append(": ").append(h[1]).append("\n");
            return sb.toString();
        }

        void start() {
            for (MonTarget t : targets) {
                Thread.ofVirtual().start(() -> runSampler(t));
            }
        }

        void runSampler(MonTarget t) {
            if (t.spec.ws()) { runWsSampler(t); return; }
            while (running && System.currentTimeMillis() < endAtMs) {
                applyLatency(net); // 弱网:模拟 RTT
                long t0 = System.currentTimeMillis();
                int code = 0;
                String err = null;
                String respBody = null;
                try {
                    HttpResponse<InputStream> resp = client.send(t.request, HttpResponse.BodyHandlers.ofInputStream());
                    code = resp.statusCode();
                    respBody = readCapped(resp.body(), 2000, netActive(net) ? net[2] : 0);
                    if (code >= 400) err = "HTTP " + code;
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                long cost = System.currentTimeMillis() - t0;
                record(t, cost, err, err == null ? String.valueOf(code) : null);
                t.reqLog.add(LocalTime.now().format(TS), t.spec.method, t.spec.fullUri().toString(),
                        code, cost, reqHeaders(t), t.spec.body, respBody);
                try { Thread.sleep(intervalMs); } catch (InterruptedException ie) { break; }
            }
            if (System.currentTimeMillis() >= endAtMs) running = false;
        }

        /** ws/wss 采样:保持一条连接,每轮发送消息并等待回复,耗时 = 发送->收到回复 */
        void runWsSampler(MonTarget t) {
            URI uri = t.spec.fullUri();
            WsClient ws = null;
            while (running && System.currentTimeMillis() < endAtMs) {
                applyLatency(net); // 弱网:模拟 RTT
                long t0 = System.currentTimeMillis();
                String err = null;
                if (ws == null) {
                    try { ws = new WsClient(client, uri); }
                    catch (Exception e) { err = abbreviate("WS连接失败: " + e.getClass().getSimpleName()); }
                }
                if (err == null) {
                    t.msgLog.add("S", t.spec.wsMessage);
                    try {
                        String[] r = ws.roundTrip(t.spec.wsMessage);
                        t.msgLog.add("R", r[1]);
                        record(t, System.currentTimeMillis() - t0, null, "WS");
                    } catch (Exception e) {
                        t.msgLog.add("R", "(无响应/错误: " + e.getClass().getSimpleName() + ")");
                        err = abbreviate("WS: " + e.getClass().getSimpleName());
                        ws.closeQuietly();
                        ws = null;
                    }
                }
                if (err != null) record(t, System.currentTimeMillis() - t0, err, null);
                try { Thread.sleep(intervalMs); } catch (InterruptedException ie) { break; }
            }
            if (ws != null) ws.closeQuietly();
            if (System.currentTimeMillis() >= endAtMs) running = false;
        }

        /** 记录一次采样结果 */
        void record(MonTarget t, long cost, String err, String codeStr) {
            t.total++;
            t.samples.add(new long[]{System.currentTimeMillis(), cost});
            if (t.samples.size() > 50_000) t.samples.remove(0); // 防内存膨胀
            if (err != null) {
                t.errCount++;
                t.slowCount++;
                slow.add(new String[]{LocalTime.now().format(TS), t.spec.url, cost + "ms", "ERR " + err});
            } else if (cost >= thresholdMs) {
                t.slowCount++;
                slow.add(new String[]{LocalTime.now().format(TS), t.spec.url, cost + "ms", codeStr});
            }
            if (slow.size() > 500) slow.remove(0);
        }
    }

    static void handleMonitorStart(HttpExchange ex) throws IOException {
        if (monitor != null && monitor.running) {
            respond(ex, 409, "text/plain; charset=utf-8", "已有监测任务在运行,请先停止");
            return;
        }
        Map<String, String> p = form(ex);
        List<TargetSpec> specs;
        try {
            specs = parseTargets(p);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "text/plain; charset=utf-8", e.getMessage());
            return;
        }
        String verr = validateSpecs(specs);
        if (verr != null) { respond(ex, 400, "text/plain; charset=utf-8", verr); return; }
        int duration = clamp(Integer.parseInt(p.getOrDefault("duration", "1800")), 10, 86400);
        int interval = clamp(Integer.parseInt(p.getOrDefault("interval", "2")), 1, 60);
        int threshold = clamp(Integer.parseInt(p.getOrDefault("threshold", "500")), 1, 600000);
        String token = p.getOrDefault("token", "");
        String tokenName = p.getOrDefault("tokenName", "authorization");
        String herr = validateHeader(token, tokenName);
        if (herr != null) { respond(ex, 400, "text/plain; charset=utf-8", herr); return; }
        int[] net = netOf(p);
        monitor = new Monitor(specs, token, tokenName, net, duration, interval, threshold);
        rememberInterfaces(specs);
        monitor.start();
        respond(ex, 200, "application/json; charset=utf-8",
                "{\"ok\":true,\"urls\":" + specs.size() + "}");
    }

    static void handleMonitorStop(HttpExchange ex) throws IOException {
        if (monitor != null) monitor.running = false;
        respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    static void handleMonitorStatus(HttpExchange ex) throws IOException {
        Monitor m = monitor;
        if (m == null) { respond(ex, 200, "application/json; charset=utf-8", "{\"running\":false,\"total\":0}"); return; }
        long total = 0, slowCount = 0, errCount = 0;
        List<Long> allLat = new ArrayList<>();
        for (MonTarget t : m.targets) {
            total += t.total;
            slowCount += t.slowCount;
            errCount += t.errCount;
            for (long[] s : t.samples) allLat.add(s[1]);
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append(json("running", m.running)).append(",")
          .append(json("urls", (long) m.targets.size())).append(",")
          .append(json("total", total)).append(",")
          .append(json("slowCount", slowCount)).append(",")
          .append(json("errCount", errCount)).append(",")
          .append(json("threshold", (long) m.thresholdMs)).append(",")
          .append(json("remainSec", (long) Math.max(0, (m.endAtMs - System.currentTimeMillis()) / 1000))).append(",")
          .append(m.net != null && netActive(m.net) ? json("net", netDesc(m.net)) : "\"net\":null").append(",")
          .append("\"percentiles\":").append(percentilesJson(allLat)).append(",");
        // 分接口统计
        sb.append("\"targets\":[");
        for (int i = 0; i < m.targets.size(); i++) {
            MonTarget t = m.targets.get(i);
            List<Long> lat = new ArrayList<>(t.samples.size());
            for (long[] s : t.samples) lat.add(s[1]);
            if (i > 0) sb.append(",");
            sb.append("{").append(json("url", t.spec.url)).append(",")
              .append(json("proto", t.spec.proto)).append(",")
              .append(json("total", (long) t.total)).append(",")
              .append(json("slowCount", (long) t.slowCount)).append(",")
              .append("\"percentiles\":").append(percentilesJson(lat))
              .append(t.spec.ws() ? ",\"messages\":" + t.msgLog.json() : ",\"reqlog\":" + t.reqLog.json())
              .append("}");
        }
        // 每接口最近 300 个采样点供画图: [[url, [[epochSec, latencyMs],...]],...]
        sb.append("],\"recentByTarget\":[");
        for (int i = 0; i < m.targets.size(); i++) {
            MonTarget t = m.targets.get(i);
            if (i > 0) sb.append(",");
            sb.append("[").append(json(t.spec.url)).append(",[");
            int from = Math.max(0, t.samples.size() - 300);
            boolean first = true;
            for (int j = from; j < t.samples.size(); j++) {
                long[] s = t.samples.get(j);
                if (!first) sb.append(",");
                sb.append("[").append(s[0] / 1000).append(",").append(s[1]).append("]");
                first = false;
            }
            sb.append("]]");
        }
        sb.append("],\"slow\":[");
        int fromS = Math.max(0, m.slow.size() - 50);
        boolean firstS = true;
        for (int i = fromS; i < m.slow.size(); i++) {
            String[] s = m.slow.get(i);
            if (!firstS) sb.append(",");
            sb.append("[").append(json(s[0])).append(",").append(json(s[1])).append(",")
              .append(json(s[2])).append(",").append(json(s[3])).append("]");
            firstS = false;
        }
        sb.append("]}");
        respond(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    // ==================== 压力测试(多接口并行,虚拟线程阶梯加压) ====================

    static volatile LoadTest loadTest; // 同时只允许一个任务(内含多个接口)

    static class LoadTarget {
        final TargetSpec spec;
        final HttpRequest request;
        final MsgLog msgLog = new MsgLog(); // ws 消息收发记录
        final ReqLog reqLog = new ReqLog(50); // http 请求/响应流水(仅采样前若干条)
        final AtomicLong captureCount = new AtomicLong();
        final AtomicLong reqs = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        final ConcurrentHashMap<String, AtomicLong> errorMap = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, long[]> perSec = new ConcurrentHashMap<>(); // sec -> {count, sumMs}
        long stageReqBefore, stageErrBefore;
        long cachedAt = 0;
        String cachedPercentiles = "{}";

        LoadTarget(TargetSpec spec, HttpRequest request) { this.spec = spec; this.request = request; }
    }

    static class LoadTest {
        static final int MAX_LATENCIES = 1_000_000; // 全部接口合计上限

        volatile boolean running = true;
        volatile boolean finished = false;
        final List<LoadTarget> targets;
        final String tokenRaw; // 用于历史记录复用
        final String tokenNameRaw; // Token 头名,用于历史记录复用
        final String[] authHeader; // {name, value} 或 null
        final int[] net; // 弱网配置
        final List<long[]> stages; // {durationSec, concurrency}
        final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        final CopyOnWriteArrayList<String> stageResults = new CopyOnWriteArrayList<>(); // 每阶段结果 JSON
        final CopyOnWriteArrayList<long[]> stageStats = new CopyOnWriteArrayList<>();   // {stage,conc,dur,reqs,errs,qps,p50,p90,p95,p99,max} 供 CSV 导出

        volatile int stageIndex = -1;
        volatile int currentConcurrency = 0;
        volatile long runStartMs, stageStartMs;
        long gCachedAt = 0;
        String gCachedPercentiles = "{}";

        LoadTest(List<TargetSpec> specs, String token, String tokenName, int[] net, List<long[]> stages) {
            this.tokenRaw = token;
            this.tokenNameRaw = tokenName;
            this.authHeader = headerFromToken(token, tokenName);
            this.net = net;
            this.stages = stages;
            long upBps = netActive(net) ? net[3] : 0;
            List<LoadTarget> list = new ArrayList<>(specs.size());
            for (TargetSpec s : specs) list.add(new LoadTarget(s, s.ws() ? null : buildRequest(s, authHeader, upBps)));
            this.targets = list;
        }

        String reqHeaders(LoadTarget t) {
            StringBuilder sb = new StringBuilder();
            if (authHeader != null) sb.append(authHeader[0]).append(": ").append(authHeader[1]).append("\n");
            for (String[] h : t.spec.headers) sb.append(h[0]).append(": ").append(h[1]).append("\n");
            return sb.toString();
        }

        /** 发送单个请求,弱网时应用延迟与下行限速;capture 时保留响应体,返回 [状态码, 响应体或null] */
        Object[] sendOnce(LoadTarget t, boolean capture) throws Exception {
            applyLatency(net); // 弱网:模拟 RTT
            int code;
            String body = null;
            if (netActive(net) && net[2] > 0) { // 弱网:下行限速读取
                HttpResponse<InputStream> resp = client.send(t.request, HttpResponse.BodyHandlers.ofInputStream());
                code = resp.statusCode();
                if (capture) body = readCapped(resp.body(), 2000, net[2]);
                else consumeThrottled(resp.body(), net[2]);
            } else if (capture) {
                HttpResponse<InputStream> resp = client.send(t.request, HttpResponse.BodyHandlers.ofInputStream());
                code = resp.statusCode();
                body = readCapped(resp.body(), 2000, 0);
            } else {
                HttpResponse<Void> resp = client.send(t.request, HttpResponse.BodyHandlers.discarding());
                code = resp.statusCode();
            }
            return new Object[]{code, body};
        }

        void start() {
            runStartMs = System.currentTimeMillis();
            Thread.ofPlatform().start(() -> {
                for (int s = 0; s < stages.size() && running; s++) {
                    long durSec = stages.get(s)[0];
                    int conc = (int) stages.get(s)[1];
                    stageIndex = s;
                    stageStartMs = System.currentTimeMillis();
                    currentConcurrency = conc;
                    for (LoadTarget t : targets) {
                        t.stageReqBefore = t.reqs.get();
                        t.stageErrBefore = t.errors.get();
                    }
                    int maxLatPerTarget = Math.max(10_000, MAX_LATENCIES / targets.size());
                    long stageEndNanos = System.nanoTime() + durSec * 1_000_000_000L;
                    CountDownLatch latch = new CountDownLatch(conc * targets.size());
                    for (int ti = 0; ti < targets.size(); ti++) {
                        LoadTarget t = targets.get(ti);
                        ConcurrentLinkedQueue<Long> stageLat = new ConcurrentLinkedQueue<>();
                        for (int i = 0; i < conc; i++) {
                            Thread.ofVirtual().start(() -> {
                                WsClient wsc = null;
                                try {
                                    if (t.spec.ws()) {
                                        try {
                                            wsc = new WsClient(client, t.spec.fullUri());
                                        } catch (Exception e) {
                                            String ce = abbreviate("WS连接失败: " + e.getClass().getSimpleName());
                                            t.errors.incrementAndGet();
                                            t.errorMap.computeIfAbsent(ce, k -> new AtomicLong()).incrementAndGet();
                                            return; // 连接失败,该 worker 直接结束(计入错误)
                                        }
                                    }
                                    while (running && System.nanoTime() < stageEndNanos) {
                                        long t0 = System.nanoTime();
                                        String err = null;
                                        String capBody = null;
                                        int capCode = 0;
                                        boolean cap = false;
                                        try {
                                            if (wsc != null) { // ws/wss
                                                t.msgLog.add("S", t.spec.wsMessage);
                                                String[] r = wsc.roundTrip(t.spec.wsMessage);
                                                t.msgLog.add("R", r[1]);
                                            } else {
                                                cap = t.captureCount.incrementAndGet() <= 50;
                                                Object[] rr = sendOnce(t, cap);
                                                capCode = (Integer) rr[0];
                                                capBody = (String) rr[1];
                                                if (capCode >= 400) err = "HTTP " + capCode;
                                            }
                                        } catch (Exception e) {
                                            err = abbreviate(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                                        }
                                        long ms = (System.nanoTime() - t0) / 1_000_000;
                                        if (cap) {
                                            t.reqLog.add(LocalTime.now().format(TS), t.spec.method, t.spec.fullUri().toString(),
                                                    capCode, ms, reqHeaders(t), t.spec.body, capBody);
                                        }
                                        t.reqs.incrementAndGet();
                                        long sec = System.currentTimeMillis() / 1000;
                                        long[] bucket = t.perSec.computeIfAbsent(sec, k -> new long[2]);
                                        synchronized (bucket) { bucket[0]++; bucket[1] += ms; }
                                        if (err != null) {
                                            t.errors.incrementAndGet();
                                            t.errorMap.computeIfAbsent(err, k -> new AtomicLong()).incrementAndGet();
                                        } else {
                                            if (t.latencies.size() < maxLatPerTarget) t.latencies.add(ms);
                                            stageLat.add(ms);
                                        }
                                    }
                                } finally {
                                    if (wsc != null) wsc.closeQuietly();
                                    latch.countDown();
                                }
                            });
                        }
                    }
                    try { latch.await(); } catch (InterruptedException ignored) {}
                    appendStageResult(s, durSec, conc);
                }
                running = false;
                finished = true;
                saveHistory(this);
                System.out.println("[load] 压测结束: 共 " + totalReqs() + " 请求, " + totalErrors() + " 错误, 记录已保存");
            });
        }

        void appendStageResult(int s, long durSec, int conc) {
            double elapsed = Math.max((System.currentTimeMillis() - stageStartMs) / 1000.0, 0.001);
            long gReqs = 0, gErrs = 0;
            StringBuilder tb = new StringBuilder();
            for (int i = 0; i < targets.size(); i++) {
                LoadTarget t = targets.get(i);
                long r = t.reqs.get() - t.stageReqBefore;
                long e = t.errors.get() - t.stageErrBefore;
                gReqs += r;
                gErrs += e;
                if (i > 0) tb.append(",");
                List<Long> latSnapshot = new ArrayList<>(t.latencies);
                tb.append("{").append(json("url", t.spec.url)).append(",")
                  .append(json("method", t.spec.method)).append(",")
                  .append(json("reqs", r)).append(",")
                  .append(json("errors", e)).append(",")
                  .append(json("qps", r / elapsed)).append(",")
                  .append("\"percentiles\":").append(percentilesJson(latSnapshot)).append("}");
            }
            stageResults.add("{" + json("stage", (long) (s + 1)) + ","
                    + json("concurrency", (long) conc) + ","
                    + json("durationSec", durSec) + ","
                    + json("reqs", gReqs) + ","
                    + json("errors", gErrs) + ","
                    + json("qps", gReqs / elapsed) + ","
                    + "\"percentiles\":" + percentilesJson(allLatencies())
                    + ",\"targets\":[" + tb + "]}");
            long[] st = statArray(allLatencies()); // {count,avg,p50,p90,p95,p99,max}
            stageStats.add(new long[]{s + 1, conc, durSec, gReqs, gErrs, Math.round(gReqs / elapsed),
                    st[2], st[3], st[4], st[5], st[6]});
            System.out.println("[load] 阶段 " + (s + 1) + "/" + stages.size() + " 完成: 并发=" + conc
                    + " reqs=" + gReqs + " errors=" + gErrs);
        }

        long totalReqs() { long n = 0; for (LoadTarget t : targets) n += t.reqs.get(); return n; }
        long totalErrors() { long n = 0; for (LoadTarget t : targets) n += t.errors.get(); return n; }

        List<Long> allLatencies() {
            List<Long> all = new ArrayList<>();
            for (LoadTarget t : targets) all.addAll(t.latencies);
            return all;
        }

        String percentilesCached() {
            long now = System.currentTimeMillis();
            if (now - gCachedAt > 2000) {
                gCachedAt = now;
                gCachedPercentiles = percentilesJson(allLatencies());
            }
            return gCachedPercentiles;
        }
    }

    static String targetPercentilesCached(LoadTarget t) {
        long now = System.currentTimeMillis();
        if (now - t.cachedAt > 2000) {
            t.cachedAt = now;
            t.cachedPercentiles = percentilesJson(new ArrayList<>(t.latencies));
        }
        return t.cachedPercentiles;
    }

    static void handleLoadStart(HttpExchange ex) throws IOException {
        if (loadTest != null && loadTest.running) {
            respond(ex, 409, "text/plain; charset=utf-8", "已有压测任务在运行,请先停止");
            return;
        }
        Map<String, String> p = form(ex);
        List<TargetSpec> specs;
        try {
            specs = parseTargets(p);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "text/plain; charset=utf-8", e.getMessage());
            return;
        }
        String verr = validateSpecs(specs);
        if (verr != null) { respond(ex, 400, "text/plain; charset=utf-8", verr); return; }
        // stages 格式: "60 5,180 20,180 50"  (时长秒 并发)
        List<long[]> stages = new ArrayList<>();
        for (String line : p.getOrDefault("stages", "60 5,180 20,180 50").split(",")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 2) {
                int dur = clamp(Integer.parseInt(f[0]), 5, 3600);
                int conc = clamp(Integer.parseInt(f[1]), 1, 2000);
                stages.add(new long[]{dur, conc});
            }
        }
        if (stages.isEmpty()) { respond(ex, 400, "text/plain; charset=utf-8", "阶段配置无效"); return; }
        String token = p.getOrDefault("token", "");
        String tokenName = p.getOrDefault("tokenName", "authorization");
        String herr = validateHeader(token, tokenName);
        if (herr != null) { respond(ex, 400, "text/plain; charset=utf-8", herr); return; }
        int[] net = netOf(p);
        loadTest = new LoadTest(specs, token, tokenName, net, stages);
        rememberInterfaces(specs);
        loadTest.start();
        respond(ex, 200, "application/json; charset=utf-8",
                "{\"ok\":true,\"stages\":" + stages.size() + ",\"urls\":" + specs.size() + "}");
    }

    static void handleLoadStop(HttpExchange ex) throws IOException {
        if (loadTest != null) loadTest.running = false;
        respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    static void handleLoadStatus(HttpExchange ex) throws IOException {
        LoadTest lt = loadTest;
        if (lt == null) { respond(ex, 200, "application/json; charset=utf-8", "{\"running\":false,\"finished\":false}"); return; }
        long nowSec = System.currentTimeMillis() / 1000;

        // 全局汇总(合并各接口的 perSec 与错误分布)
        TreeMap<Long, long[]> merged = new TreeMap<>();
        Map<String, AtomicLong> errMerged = new LinkedHashMap<>();
        for (LoadTarget t : lt.targets) {
            t.perSec.forEach((sec, b) -> {
                long[] m = merged.computeIfAbsent(sec, k -> new long[2]);
                synchronized (m) { m[0] += b[0]; m[1] += b[1]; }
            });
            t.errorMap.forEach((k, v) -> errMerged.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(v.get()));
        }
        long gReqs = lt.totalReqs(), gErrs = lt.totalErrors();
        long qpsSum = 0;
        int qpsN = 0;
        for (Map.Entry<Long, long[]> e : merged.entrySet()) {
            if (e.getKey() < nowSec && e.getKey() >= nowSec - 10) { qpsSum += e.getValue()[0]; qpsN++; }
        }

        StringBuilder sb = new StringBuilder("{");
        sb.append(json("running", lt.running)).append(",")
          .append(json("finished", lt.finished)).append(",")
          .append(json("urls", (long) lt.targets.size())).append(",")
          .append(json("stageIndex", (long) lt.stageIndex)).append(",")
          .append(json("stageTotal", (long) lt.stages.size())).append(",")
          .append(json("stageConcurrency", (long) lt.currentConcurrency)).append(",")
          .append(json("elapsedSec", (System.currentTimeMillis() - lt.runStartMs) / 1000)).append(",")
          .append(json("totalReqs", gReqs)).append(",")
          .append(json("totalErrors", gErrs)).append(",")
          .append(json("errorRate", gReqs == 0 ? 0 : gErrs * 100.0 / gReqs)).append(",")
          .append(json("qps", qpsN == 0 ? 0 : qpsSum / (double) qpsN)).append(",")
          .append(lt.net != null && netActive(lt.net) ? json("net", netDesc(lt.net)) : "\"net\":null").append(",")
          .append("\"percentiles\":").append(lt.percentilesCached()).append(",");
        // 分接口实时统计
        sb.append("\"targets\":[");
        for (int i = 0; i < lt.targets.size(); i++) {
            LoadTarget t = lt.targets.get(i);
            long tSum = 0; int tN = 0;
            for (Map.Entry<Long, long[]> e : t.perSec.entrySet()) {
                if (e.getKey() < nowSec && e.getKey() >= nowSec - 10) { tSum += e.getValue()[0]; tN++; }
            }
            long r = t.reqs.get(), e2 = t.errors.get();
            if (i > 0) sb.append(",");
            sb.append("{").append(json("url", t.spec.url)).append(",")
              .append(json("proto", t.spec.proto)).append(",")
              .append(json("method", t.spec.ws() ? "WS" : t.spec.method)).append(",")
              .append(json("reqs", r)).append(",")
              .append(json("errors", e2)).append(",")
              .append(json("errorRate", r == 0 ? 0 : e2 * 100.0 / r)).append(",")
              .append(json("qps", tN == 0 ? 0 : tSum / (double) tN)).append(",")
              .append("\"percentiles\":").append(targetPercentilesCached(t))
              .append(t.spec.ws() ? ",\"messages\":" + t.msgLog.json() : ",\"reqlog\":" + t.reqLog.json())
              .append("}");
        }
        // 每秒序列(全局合并,最近 300 秒): [[sec, count, avgMs],...]
        sb.append("],\"series\":[");
        boolean first = true;
        int skip = Math.max(0, merged.size() - 300);
        int idx = 0;
        for (Map.Entry<Long, long[]> e : merged.entrySet()) {
            if (idx++ < skip) continue;
            long[] b = e.getValue();
            if (!first) sb.append(",");
            sb.append("[").append(e.getKey()).append(",").append(b[0]).append(",")
              .append(Math.round(b[1] * 10.0 / Math.max(b[0], 1)) / 10.0).append("]");
            first = false;
        }
        sb.append("],\"stageResults\":[");
        for (int i = 0; i < lt.stageResults.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(lt.stageResults.get(i));
        }
        sb.append("],\"errors\":{");
        boolean firstE = true;
        for (Map.Entry<String, AtomicLong> e : errMerged.entrySet()) {
            if (!firstE) sb.append(",");
            sb.append(json(e.getKey())).append(":").append(e.getValue().get());
            firstE = false;
        }
        sb.append("}}");
        respond(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    // ==================== 结果导出(CSV / JSON) ====================

    static String nowStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 统计数组: {count, avg, p50, p90, p95, p99, max} */
    static long[] statArray(List<Long> lat) {
        if (lat.isEmpty()) return new long[]{0, 0, 0, 0, 0, 0, 0};
        long[] a = new long[lat.size()];
        for (int i = 0; i < a.length; i++) a[i] = lat.get(i);
        Arrays.sort(a);
        double avg = Arrays.stream(a).average().orElse(0);
        return new long[]{a.length, Math.round(avg), a[idx(a, .50)], a[idx(a, .90)], a[idx(a, .95)], a[idx(a, .99)], a[a.length - 1]};
    }

    static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static void handleMonitorExport(HttpExchange ex) throws IOException {
        Monitor m = monitor;
        String format = query(ex).getOrDefault("format", "json");
        if (m == null || m.targets.isEmpty()) {
            respond(ex, 200, "application/json; charset=utf-8", "{\"error\":\"暂无监测数据,请先运行监测\"}");
            return;
        }
        if ("csv".equalsIgnoreCase(format)) {
            respond(ex, 200, "text/csv; charset=utf-8", monitorCsv(m));
        } else {
            respond(ex, 200, "application/json; charset=utf-8", monitorJson(m));
        }
    }

    static String monitorJson(Monitor m) {
        long total = 0, slow = 0, err = 0;
        StringBuilder targets = new StringBuilder("[");
        StringBuilder samples = new StringBuilder("[");
        for (int i = 0; i < m.targets.size(); i++) {
            MonTarget t = m.targets.get(i);
            total += t.total;
            slow += t.slowCount;
            err += t.errCount;
            List<Long> lat = new ArrayList<>(t.samples.size());
            for (long[] s : t.samples) lat.add(s[1]);
            if (i > 0) targets.append(",");
            targets.append("{").append(json("url", t.spec.url)).append(",")
                    .append(json("total", (long) t.total)).append(",")
                    .append(json("slowCount", (long) t.slowCount)).append(",")
                    .append("\"percentiles\":").append(percentilesJson(lat)).append("}");
            for (long[] s : t.samples) {
                if (samples.length() > 1) samples.append(",");
                samples.append("[").append(json(t.spec.url)).append(",").append(s[0] / 1000).append(",").append(s[1]).append("]");
            }
        }
        targets.append("]");
        samples.append("]");
        StringBuilder slowArr = new StringBuilder("[");
        for (int i = 0; i < m.slow.size(); i++) {
            String[] s = m.slow.get(i);
            if (i > 0) slowArr.append(",");
            slowArr.append("[").append(json(s[0])).append(",").append(json(s[1])).append(",")
                   .append(json(s[2])).append(",").append(json(s[3])).append("]");
        }
        slowArr.append("]");
        List<Long> all = new ArrayList<>();
        for (MonTarget t : m.targets) for (long[] s : t.samples) all.add(s[1]);
        return "{\"type\":\"monitor\",\"time\":\"" + nowStr() + "\","
                + "\"total\":" + total + ",\"slowCount\":" + slow + ",\"errCount\":" + err + ","
                + "\"percentiles\":" + percentilesJson(all) + ","
                + "\"targets\":" + targets + ",\"samples\":" + samples + ",\"slow\":" + slowArr + "}";
    }

    static String monitorCsv(Monitor m) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("采样监测结果导出,,,,,,,\r\n");
        sb.append("导出时间,").append(csv(nowStr())).append("\r\n");
        List<Long> all = new ArrayList<>();
        for (MonTarget t : m.targets) for (long[] s : t.samples) all.add(s[1]);
        long[] st = statArray(all);
        sb.append("总采样数,").append(all.size()).append("\r\n");
        sb.append("慢/异常,").append(m.slow.size()).append("\r\n");
        sb.append("\r\n指标,P50(ms),P90(ms),P95(ms),P99(ms),MAX(ms),平均(ms)\r\n");
        sb.append("全局,").append(st[2]).append(",").append(st[3]).append(",").append(st[4]).append(",")
          .append(st[5]).append(",").append(st[6]).append(",").append(st[1]).append("\r\n");

        sb.append("\r\n# 分接口统计\r\n");
        sb.append("接口,采样数,慢/异常,P50(ms),P90(ms),P95(ms),P99(ms),MAX(ms)\r\n");
        for (MonTarget t : m.targets) {
            List<Long> lat = new ArrayList<>();
            for (long[] s : t.samples) lat.add(s[1]);
            long[] p = statArray(lat);
            sb.append(csv(t.spec.url)).append(",").append(t.total).append(",").append(t.slowCount).append(",")
              .append(p[2]).append(",").append(p[3]).append(",").append(p[4]).append(",").append(p[5]).append(",").append(p[6]).append("\r\n");
        }

        sb.append("\r\n# 采样明细\r\n");
        sb.append("接口,时间戳(秒),耗时(ms)\r\n");
        for (MonTarget t : m.targets) {
            for (long[] s : t.samples) {
                sb.append(csv(t.spec.url)).append(",").append(s[0] / 1000).append(",").append(s[1]).append("\r\n");
            }
        }

        sb.append("\r\n# 慢请求/异常\r\n");
        sb.append("时间,接口,耗时,状态\r\n");
        for (String[] s : m.slow) {
            sb.append(csv(s[0])).append(",").append(csv(s[1])).append(",").append(csv(s[2])).append(",").append(csv(s[3])).append("\r\n");
        }
        return sb.toString();
    }

    static void handleLoadExport(HttpExchange ex) throws IOException {
        LoadTest lt = loadTest;
        String format = query(ex).getOrDefault("format", "json");
        if (lt == null || lt.totalReqs() == 0) {
            respond(ex, 200, "application/json; charset=utf-8", "{\"error\":\"暂无压测数据,请先运行压测\"}");
            return;
        }
        if ("csv".equalsIgnoreCase(format)) {
            respond(ex, 200, "text/csv; charset=utf-8", loadCsv(lt));
        } else {
            respond(ex, 200, "application/json; charset=utf-8", loadJson(lt));
        }
    }

    static String loadJson(LoadTest lt) {
        long gReqs = lt.totalReqs(), gErrs = lt.totalErrors();
        StringBuilder targets = new StringBuilder("[");
        for (int i = 0; i < lt.targets.size(); i++) {
            LoadTarget t = lt.targets.get(i);
            long r = t.reqs.get(), e = t.errors.get();
            if (i > 0) targets.append(",");
            targets.append("{").append(json("url", t.spec.url)).append(",")
                    .append(json("method", t.spec.ws() ? "WS" : t.spec.method)).append(",")
                    .append(json("reqs", r)).append(",")
                    .append(json("errors", e)).append(",")
                    .append(json("errorRate", r == 0 ? 0 : e * 100.0 / r)).append(",")
                    .append("\"percentiles\":").append(targetPercentilesCached(t)).append("}");
        }
        targets.append("]");
        Map<String, AtomicLong> errMerged = new LinkedHashMap<>();
        for (LoadTarget t : lt.targets) {
            t.errorMap.forEach((k, v) -> errMerged.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(v.get()));
        }
        StringBuilder errs = new StringBuilder("{");
        boolean firstE = true;
        for (Map.Entry<String, AtomicLong> e : errMerged.entrySet()) {
            if (!firstE) errs.append(",");
            errs.append(json(e.getKey())).append(":").append(e.getValue().get());
            firstE = false;
        }
        errs.append("}");
        return "{\"type\":\"load\",\"time\":\"" + nowStr() + "\","
                + "\"totalReqs\":" + gReqs + ",\"totalErrors\":" + gErrs + ","
                + "\"errorRate\":" + (gReqs == 0 ? 0 : gErrs * 100.0 / gReqs) + ","
                + "\"percentiles\":" + lt.percentilesCached() + ","
                + "\"targets\":" + targets + ","
                + "\"stageResults\":[" + String.join(",", lt.stageResults) + "],"
                + "\"errors\":" + errs + "}";
    }

    static String loadCsv(LoadTest lt) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("压测结果导出,,,,,,,,,,\r\n");
        sb.append("导出时间,").append(csv(nowStr())).append("\r\n");
        long gReqs = lt.totalReqs(), gErrs = lt.totalErrors();
        long[] st = statArray(lt.allLatencies());
        double elapsed = Math.max((System.currentTimeMillis() - lt.runStartMs) / 1000.0, 1);
        sb.append("总请求,").append(gReqs).append("\r\n");
        sb.append("总错误,").append(gErrs).append("\r\n");
        sb.append("错误率(%),").append(String.format("%.2f", gReqs == 0 ? 0 : gErrs * 100.0 / gReqs)).append("\r\n");
        sb.append("平均QPS,").append(Math.round(gReqs / elapsed)).append("\r\n");
        sb.append("\r\n指标,P50(ms),P90(ms),P95(ms),P99(ms),MAX(ms),平均(ms)\r\n");
        sb.append("全局,").append(st[2]).append(",").append(st[3]).append(",").append(st[4]).append(",")
          .append(st[5]).append(",").append(st[6]).append(",").append(st[1]).append("\r\n");

        sb.append("\r\n# 分接口统计\r\n");
        sb.append("接口,方法,请求数,错误数,错误率(%),P50(ms),P95(ms),P99(ms),MAX(ms)\r\n");
        for (LoadTarget t : lt.targets) {
            long r = t.reqs.get(), e = t.errors.get();
            long[] p = statArray(new ArrayList<>(t.latencies));
            sb.append(csv(t.spec.url)).append(",").append(t.spec.ws() ? "WS" : t.spec.method).append(",")
              .append(r).append(",").append(e).append(",").append(String.format("%.2f", r == 0 ? 0 : e * 100.0 / r)).append(",")
              .append(p[2]).append(",").append(p[4]).append(",").append(p[5]).append(",").append(p[6]).append("\r\n");
        }

        sb.append("\r\n# 阶段结果\r\n");
        sb.append("阶段,并发,时长(s),请求数,错误数,QPS,P50(ms),P90(ms),P95(ms),P99(ms),MAX(ms)\r\n");
        for (long[] g : lt.stageStats) {
            sb.append(g[0]).append(",").append(g[1]).append(",").append(g[2]).append(",").append(g[3]).append(",")
              .append(g[4]).append(",").append(g[5]).append(",").append(g[6]).append(",").append(g[7]).append(",")
              .append(g[8]).append(",").append(g[9]).append(",").append(g[10]).append("\r\n");
        }

        Map<String, AtomicLong> errMerged = new LinkedHashMap<>();
        for (LoadTarget t : lt.targets) {
            t.errorMap.forEach((k, v) -> errMerged.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(v.get()));
        }
        if (!errMerged.isEmpty()) {
            sb.append("\r\n# 错误分布\r\n");
            sb.append("错误信息,次数\r\n");
            for (Map.Entry<String, AtomicLong> e : errMerged.entrySet()) {
                sb.append(csv(e.getKey())).append(",").append(e.getValue().get()).append("\r\n");
            }
        }
        return sb.toString();
    }

    // ==================== 冒烟导出 ====================

    static void handleSmokeExport(HttpExchange ex) throws IOException {
        String format = query(ex).getOrDefault("format", "json");
        if (lastSmoke == null || lastSmoke.isEmpty()) {
            respond(ex, 200, "application/json; charset=utf-8", "{\"error\":\"暂无冒烟测试数据,请先运行\"}");
            return;
        }
        if ("csv".equalsIgnoreCase(format)) {
            respond(ex, 200, "text/csv; charset=utf-8", smokeCsv());
        } else {
            respond(ex, 200, "application/json; charset=utf-8", smokeJson());
        }
    }

    static String smokeJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < lastSmoke.size(); r++) {
            SmokeResult sr = lastSmoke.get(r);
            if (r > 0) sb.append(",");
            sb.append("{").append(json("url", sr.url)).append(",").append(json("proto", sr.proto))
              .append(",").append(json("method", sr.method)).append(",\"rows\":[");
            for (int i = 0; i < sr.rows.size(); i++) {
                SmokeRow row = sr.rows.get(i);
                if (i > 0) sb.append(",");
                if (row.error != null) {
                    sb.append("{").append(json("seq", row.seq)).append(",").append(json("error", row.error)).append("}");
                } else {
                    sb.append("{").append(json("seq", row.seq)).append(",").append(json("dns", row.dns))
                      .append(",").append(json("connect", row.connect)).append(",").append(json("tls", row.tls))
                      .append(",").append(json("firstByte", row.firstByte)).append(",").append(json("total", row.total))
                      .append(",").append(json("code", row.code)).append(",").append(json("respBody", truncate(row.respBody, 2000))).append("}");
                }
            }
            sb.append("]}");
        }
        sb.append("]");
        return "{\"type\":\"smoke\",\"time\":\"" + nowStr() + "\",\"results\":" + sb + "}";
    }

    static String smokeCsv() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("冒烟测试结果导出,,,,,,,,\r\n");
        sb.append("导出时间,").append(csv(nowStr())).append("\r\n\r\n");
        sb.append("接口,方法,序号,DNS(ms),TCP连接(ms),TLS(ms),首字节(ms),总耗时(ms),状态码/错误,响应体\r\n");
        for (SmokeResult sr : lastSmoke) {
            for (SmokeRow row : sr.rows) {
                sb.append(csv(sr.url)).append(",").append(sr.method).append(",").append(row.seq).append(",");
                if (row.error != null) {
                    sb.append(",,,,,,").append(csv(row.error)).append(",\r\n");
                } else {
                    sb.append(row.dns).append(",").append(row.connect).append(",").append(row.tls).append(",")
                      .append(row.firstByte).append(",").append(row.total).append(",").append(row.code).append(",")
                      .append(csv(truncate(row.respBody, 2000))).append("\r\n");
                }
            }
        }
        return sb.toString();
    }

    // ==================== 历史调用接口(去重,快速选择) ====================

    static Path interfacesFile() { return dataDir().resolve("interfaces.tsv"); }

    static void loadInterfaces() {
        synchronized (interfaceHistory) {
            if (interfacesLoaded) return;
            interfacesLoaded = true;
            Path f = interfacesFile();
            if (!Files.exists(f)) return;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    String[] p = line.split("\t");
                    if (p.length >= 3) {
                        interfaceHistory.add(new String[]{p[0], p[1], p[2]});
                        interfaceKeys.add(p[1] + "://" + p[0]);
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    static void rememberInterfaces(List<TargetSpec> specs) {
        loadInterfaces();
        boolean changed = false;
        synchronized (interfaceHistory) {
            for (TargetSpec s : specs) {
                String key = s.proto + "://" + s.url;
                if (interfaceKeys.add(key)) {
                    interfaceHistory.add(new String[]{s.url, s.proto, s.method});
                    changed = true;
                }
            }
            while (interfaceHistory.size() > 200) {
                String[] r = interfaceHistory.remove(0);
                interfaceKeys.remove(r[1] + "://" + r[0]);
            }
        }
        if (changed) {
            try {
                Files.createDirectories(dataDir());
                StringBuilder sb = new StringBuilder();
                for (String[] e : interfaceHistory) {
                    sb.append(e[0]).append('\t').append(e[1]).append('\t').append(e[2]).append('\n');
                }
                Files.writeString(interfacesFile(), sb.toString());
            } catch (Exception ignored) { }
        }
    }

    static void handleInterfaceHistory(HttpExchange ex) throws IOException {
        loadInterfaces();
        synchronized (interfaceHistory) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = interfaceHistory.size() - 1; i >= 0; i--) { // 最新在前
                String[] e = interfaceHistory.get(i);
                if (sb.length() > 1) sb.append(",");
                sb.append("{").append(json("url", e[0])).append(",").append(json("proto", e[1]))
                  .append(",").append(json("method", e[2])).append("}");
            }
            sb.append("]");
            respond(ex, 200, "application/json; charset=utf-8", sb.toString());
        }
    }

    static void handleInterfaceClear(HttpExchange ex) throws IOException {
        synchronized (interfaceHistory) {
            interfaceHistory.clear();
            interfaceKeys.clear();
        }
        try { Files.deleteIfExists(interfacesFile()); } catch (Exception ignored) { }
        respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    // ==================== 压测记录(自动保存到 ./history/) ====================

    static Path historyDir() {
        return dataDir().resolve("history");
    }

    static void saveHistory(LoadTest lt) {
        try {
            long gReqs = lt.totalReqs(), gErrs = lt.totalErrors();
            if (gReqs == 0) return;
            Path dir = historyDir();
            Files.createDirectories(dir);
            LocalDateTime now = LocalDateTime.now();
            String id = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            while (Files.exists(dir.resolve(id + ".json"))) id += "x";
            double elapsed = Math.max((System.currentTimeMillis() - lt.runStartMs) / 1000.0, 1);

            // 配置
            StringBuilder cfg = new StringBuilder("{\"targets\":[");
            for (int i = 0; i < lt.targets.size(); i++) {
                TargetSpec s = lt.targets.get(i).spec;
                if (i > 0) cfg.append(",");
                cfg.append("{").append(json("url", s.url)).append(",")
                   .append(json("proto", s.proto)).append(",")
                   .append(json("method", s.method)).append(",")
                   .append(json("body", s.body)).append(",")
                   .append(json("query", s.query)).append(",")
                   .append(json("wsMessage", s.wsMessage)).append(",\"headers\":{");
                for (int j = 0; j < s.headers.size(); j++) {
                    if (j > 0) cfg.append(",");
                    cfg.append(json(s.headers.get(j)[0])).append(":").append(json(s.headers.get(j)[1]));
                }
                cfg.append("}}");
            }
            cfg.append("],\"stages\":[");
            for (int i = 0; i < lt.stages.size(); i++) {
                if (i > 0) cfg.append(",");
                cfg.append("[").append(lt.stages.get(i)[0]).append(",").append(lt.stages.get(i)[1]).append("]");
            }
            cfg.append("],\"net\":[").append(lt.net[0]).append(",").append(lt.net[1]).append(",")
               .append(lt.net[2] / 1024).append(",").append(lt.net[3] / 1024).append("]")
               .append(",").append(json("token", lt.tokenRaw))
               .append(",").append(json("tokenName", lt.tokenNameRaw)).append("}");

            // 结果
            List<Long> all = lt.allLatencies();
            String res = "{" + json("totalReqs", gReqs) + ","
                    + json("totalErrors", gErrs) + ","
                    + json("errorRate", gErrs * 100.0 / gReqs) + ","
                    + json("qps", gReqs / elapsed) + ","
                    + json("durationSec", (long) elapsed) + ","
                    + "\"percentiles\":" + percentilesJson(all) + ","
                    + "\"stageResults\":[" + String.join(",", lt.stageResults) + "]}";

            String time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String record = "{" + json("id", id) + "," + json("time", time)
                    + ",\"config\":" + cfg + ",\"result\":" + res + "}";
            Files.writeString(dir.resolve(id + ".json"), record);

            // 摘要索引(每行一个 JSON 对象)
            StringBuilder urls = new StringBuilder("[");
            for (int i = 0; i < lt.targets.size(); i++) {
                if (i > 0) urls.append(",");
                urls.append(json(lt.targets.get(i).spec.url));
            }
            urls.append("]");
            String summary = "{" + json("id", id) + "," + json("time", time)
                    + ",\"urls\":" + urls + ","
                    + json("totalReqs", gReqs) + ","
                    + json("errorRate", gErrs * 100.0 / gReqs) + ","
                    + json("qps", gReqs / elapsed) + ","
                    + json("p95", pval(all, 0.95)) + ","
                    + json("p99", pval(all, 0.99)) + ","
                    + (netActive(lt.net) ? json("net", netDesc(lt.net)) : "\"net\":null") + "}";
            Files.writeString(dir.resolve("index.jsonl"), summary + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("[history] 保存失败: " + e);
        }
    }

    static void handleHistoryList(HttpExchange ex) throws IOException {
        Path idx = historyDir().resolve("index.jsonl");
        if (!Files.exists(idx)) { respond(ex, 200, "application/json; charset=utf-8", "[]"); return; }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String line : Files.readAllLines(idx, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            if (!first) sb.append(",");
            sb.append(line.trim());
            first = false;
        }
        sb.append("]");
        respond(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    static void handleHistoryGet(HttpExchange ex) throws IOException {
        String id = query(ex).getOrDefault("id", "");
        if (!id.matches("[A-Za-z0-9x-]+")) { respond(ex, 400, "text/plain; charset=utf-8", "非法 id"); return; }
        Path f = historyDir().resolve(id + ".json");
        if (!Files.exists(f)) { respond(ex, 404, "text/plain; charset=utf-8", "记录不存在"); return; }
        respond(ex, 200, "application/json; charset=utf-8", Files.readString(f));
    }

    static void handleHistoryDelete(HttpExchange ex) throws IOException {
        Map<String, String> p = ex.getRequestMethod().equals("POST") ? form(ex) : query(ex);
        String id = p.getOrDefault("id", "");
        if (!id.matches("[A-Za-z0-9x-]+")) { respond(ex, 400, "text/plain; charset=utf-8", "非法 id"); return; }
        Path f = historyDir().resolve(id + ".json");
        boolean deleted = Files.deleteIfExists(f);
        // 重写索引,去掉该 id 的行
        Path idx = historyDir().resolve("index.jsonl");
        if (Files.exists(idx)) {
            List<String> keep = new ArrayList<>();
            for (String line : Files.readAllLines(idx, StandardCharsets.UTF_8)) {
                if (!line.contains("\"id\":\"" + id + "\"")) keep.add(line);
            }
            Files.write(idx, keep, StandardCharsets.UTF_8);
        }
        respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true,\"deleted\":" + deleted + "}");
    }

    // ==================== 弱网模拟引擎 ====================
    // net[0]=延迟ms net[1]=抖动ms net[2]=下行B/s net[3]=上行B/s(0 表示不限)

    static int[] netOf(Map<String, String> p) {
        return new int[]{
                clamp(intOr(p.get("netLatency"), 0), 0, 10000),
                clamp(intOr(p.get("netJitter"), 0), 0, 5000),
                clamp(intOr(p.get("netDown"), 0), 0, 100000) * 1024,
                clamp(intOr(p.get("netUp"), 0), 0, 100000) * 1024
        };
    }

    static int intOr(String s, int def) {
        try { return s == null || s.isBlank() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    static boolean netActive(int[] net) {
        return net != null && (net[0] > 0 || net[1] > 0 || net[2] > 0 || net[3] > 0);
    }

    static String netDesc(int[] net) {
        if (!netActive(net)) return null;
        StringBuilder sb = new StringBuilder("延迟 ").append(net[0]).append("ms");
        if (net[1] > 0) sb.append("±").append(net[1]).append("ms");
        if (net[2] > 0) sb.append(" · 下行 ").append(net[2] / 1024).append("KB/s");
        if (net[3] > 0) sb.append(" · 上行 ").append(net[3] / 1024).append("KB/s");
        return sb.toString();
    }

    /** 模拟上行/RTT 延迟:固定延迟 + [0, jitter] 随机抖动 */
    static void applyLatency(int[] net) {
        if (net == null) return;
        long ms = net[0];
        if (net[1] > 0) ms += ThreadLocalRandom.current().nextLong(net[1] + 1);
        if (ms > 0) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** 按限速读取并丢弃响应体(下行限速) */
    static void consumeThrottled(InputStream in, long bytesPerSec) throws IOException {
        readCapped(in, 0, bytesPerSec);
    }

    /** 读取响应体(完整读完后返回),最多保留 maxBytes 字节;bytesPerSec>0 时按限速读取 */
    static String readCapped(InputStream in, int maxBytes, long bytesPerSec) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n, total = 0;
        long start = System.nanoTime();
        try (in) {
            while ((n = in.read(b)) > 0) {
                if (total < maxBytes) {
                    int take = Math.min(n, maxBytes - total);
                    buf.write(b, 0, take);
                }
                total += n;
                if (bytesPerSec > 0) {
                    long targetNs = total * 1_000_000_000L / bytesPerSec;
                    long elapsed = System.nanoTime() - start;
                    if (targetNs > elapsed) {
                        try { Thread.sleep((targetNs - elapsed) / 1_000_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** 上行限速:包装 BodyPublisher,按字节比例 sleep */
    static class ThrottledPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final long bytesPerSec;

        ThrottledPublisher(HttpRequest.BodyPublisher delegate, long bytesPerSec) {
            this.delegate = delegate;
            this.bytesPerSec = bytesPerSec;
        }

        @Override public long contentLength() { return delegate.contentLength(); }

        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(new Flow.Subscriber<>() {
                long sent = 0, start = 0;
                @Override public void onSubscribe(Flow.Subscription s) { subscriber.onSubscribe(s); start = System.nanoTime(); }
                @Override public void onNext(ByteBuffer item) {
                    subscriber.onNext(item);
                    sent += item.remaining();
                    long targetNs = sent * 1_000_000_000L / bytesPerSec;
                    long elapsed = System.nanoTime() - start;
                    if (targetNs > elapsed) {
                        try { Thread.sleep((targetNs - elapsed) / 1_000_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                @Override public void onError(Throwable t) { subscriber.onError(t); }
                @Override public void onComplete() { subscriber.onComplete(); }
            });
        }
    }

    // ==================== 通用工具 ====================

    /** 解析多接口列表:urls 参数按行分隔,兼容单接口的 url 参数(旧版格式,供命令行使用) */
    static List<String> parseUrls(Map<String, String> p) {
        String raw = p.containsKey("urls") && !p.get("urls").isBlank() ? p.get("urls") : p.getOrDefault("url", "");
        List<String> urls = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String u = line.trim();
            if (!u.isEmpty()) urls.add(u);
        }
        return urls;
    }

    static long pval(List<Long> lat, double q) {
        if (lat.isEmpty()) return 0;
        long[] a = new long[lat.size()];
        for (int i = 0; i < a.length; i++) a[i] = lat.get(i);
        Arrays.sort(a);
        return a[idx(a, q)];
    }

    static String percentilesJson(List<Long> lat) {
        if (lat.isEmpty()) return "{}";
        long[] a = new long[lat.size()];
        for (int i = 0; i < a.length; i++) a[i] = lat.get(i);
        Arrays.sort(a);
        double avg = Arrays.stream(a).average().orElse(0);
        return "{" + json("count", (long) a.length) + ","
                + json("avg", avg) + ","
                + json("p50", a[idx(a, 0.50)]) + ","
                + json("p90", a[idx(a, 0.90)]) + ","
                + json("p95", a[idx(a, 0.95)]) + ","
                + json("p99", a[idx(a, 0.99)]) + ","
                + json("max", a[a.length - 1]) + "}";
    }

    static int idx(long[] a, double q) { return (int) Math.min(a.length - 1, (long) (a.length * q)); }

    static String abbreviate(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /**
     * Token 转请求头:
     *   "abc123"            -> Authorization: Bearer abc123
     *   "X-Token: abc123"   -> X-Token: abc123(带冒号则按完整 Header 处理)
     *   空                  -> null(不加头)
     */
    static String[] headerFromToken(String token, String tokenName) {
        if (token == null || token.isBlank()) return null;
        token = token.trim();
        int i = token.indexOf(':');
        if (i > 0 && token.substring(0, i).matches("[A-Za-z0-9-]+")) {
            return new String[]{token.substring(0, i).trim(), token.substring(i + 1).trim()};
        }
        String name = (tokenName == null || tokenName.isBlank()) ? "authorization" : tokenName.trim();
        return new String[]{name, token};
    }

    /** 校验 token 能否作为合法 Header(如含中文会抛异常),返回错误信息或 null */
    static String validateHeader(String token, String tokenName) {
        String[] h = headerFromToken(token, tokenName);
        if (h == null) return null;
        try {
            HttpRequest.newBuilder(URI.create("http://localhost/")).header(h[0], h[1]).build();
            return null;
        } catch (IllegalArgumentException e) {
            return "Token 名或 Token 值无法作为请求头发送(不能包含中文/特殊字符): " + e.getMessage();
        }
    }

    static String json(String k, long v) { return "\"" + esc(k) + "\":" + v; }
    static String json(String k, boolean v) { return "\"" + esc(k) + "\":" + v; }
    static String json(String k, double v) { return "\"" + esc(k) + "\":" + (Math.round(v * 100.0) / 100.0); }
    static String json(String k, String v) { return "\"" + esc(k) + "\":\"" + esc(v) + "\""; }
    static String json(String v) { return "\"" + esc(v) + "\""; }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> { }
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseKv(body);
    }

    static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? Map.of() : parseKv(q);
    }

    static Map<String, String> parseKv(String kv) {
        Map<String, String> m = new HashMap<>();
        for (String pair : kv.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
