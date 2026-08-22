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
