// 原生窗口:JavaFX WebView 加载本地服务页面
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.net.InetSocketAddress;
import java.net.Socket;

public class AppWindow extends Application {
    public static int PORT = 8080;

    @Override
    public void start(Stage stage) {
        WebView view = new WebView();
        WebEngine engine = view.getEngine();

        // WebView 默认吞掉 JS 的 alert(),这里转成页面内的 toast 提示(不依赖原生对话框)
        engine.setOnAlert(e -> {
            String js = "if(window.toast){toast(" + jsStr(e.getData()) + ",true);}";
            engine.executeScript(js);
        });

        StackPane root = new StackPane(view);
        Scene scene = new Scene(root, 1000, 900);

        stage.setScene(scene);
        stage.setTitle("压测 v" + PerfServer.VERSION);
        stage.setMinWidth(800);
        stage.setMinHeight(800);
        stage.show();

        // 等 HTTP 服务就绪后再加载(避免启动瞬间连接被拒)
        String url = "http://127.0.0.1:" + PORT + "/";
        new Thread(() -> {
            waitForServer(PORT);
            Platform.runLater(() -> engine.load(url));
        }).start();
    }

    /** 把 Java 字符串转成 JS 双引号字符串字面量(用于 executeScript 拼接) */
    private static String jsStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static void waitForServer(int port) {
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (Exception e) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
        }
    }
}
