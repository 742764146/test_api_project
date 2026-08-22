// 打包入口:后台启动 HTTP 服务 + 打开原生 JavaFX 窗口(非浏览器)
// 注意:main 不能继承 Application,否则类路径下启动 JavaFX 会报"runtime components missing"
import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        // 后台启动 HTTP 服务(端口被占用 = 已在运行,窗口仍指向现有实例)
        Thread server = new Thread(() -> {
            try {
                PerfServer.startServer(port);
                System.out.println("服务已启动: http://127.0.0.1:" + port);
            } catch (java.net.BindException e) {
                System.out.println("服务已在运行,复用现有实例: http://127.0.0.1:" + port);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        server.setDaemon(true);
        server.start();

        // 打开原生窗口
        AppWindow.PORT = port;
        Application.launch(AppWindow.class, args);
    }
}
