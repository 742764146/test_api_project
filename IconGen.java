// 图标生成器:绘制"压测"测速仪表图标,输出 PNG(多尺寸)与 Windows .ico
// 运行: java IconGen.java
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IconGen {

    public static void main(String[] args) throws Exception {
        Path assets = Path.of("assets");
        Files.createDirectories(assets);

        // 主图 1024x1024
        BufferedImage master = drawIcon(1024);
        ImageIO.write(master, "png", assets.resolve("icon.png").toFile());

        // 多尺寸 PNG(供 icns / 预览)
        int[] sizes = {512, 256, 128, 64, 32, 16};
        for (int s : sizes) {
            ImageIO.write(scale(master, s), "png", assets.resolve("icon_" + s + ".png").toFile());
        }

        // Windows ICO(内嵌 PNG 条目,256/64/48/32/16)
        writeIco(assets.resolve("app.ico"), master, new int[]{256, 64, 48, 32, 16});

        System.out.println("图标已生成到 assets/ 目录 (icon.png + 多尺寸 PNG + app.ico)");
        System.out.println("下一步生成 app.icns: 运行 ./build-icons.sh 或执行 icns 命令(见 PACKAGE.md)");
    }

    /** 绘制测速仪表图标 */
    static BufferedImage drawIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        float s = size / 1024f;

        // 圆角方形背景(蓝渐变)
        GradientPaint bg = new GradientPaint(0, 0, new Color(0x4a7cef), size, size, new Color(0x1c46c0));
        g.setPaint(bg);
        g.fillRoundRect(0, 0, size, size, (int) (200 * s), (int) (200 * s));

        // 仪表盘外圈(半透明白)
        int cx = size / 2, cy = (int) (560 * s);
        int r = (int) (300 * s);
        g.setStroke(new BasicStroke((int) (64 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 50));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 180, 180, Arc2D.OPEN));

        // 彩色弧段(绿→黄→红,体现"测速"等级)
        g.setStroke(new BasicStroke((int) (64 * s), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x3ddc84));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 180, 60, Arc2D.OPEN));
        g.setColor(new Color(0xffd166));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 240, 60, Arc2D.OPEN));
        g.setColor(new Color(0xff5d5d));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 300, 60, Arc2D.OPEN));

        // 刻度
        g.setStroke(new BasicStroke((int) (10 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        for (int i = 0; i <= 10; i++) {
            double ang = Math.toRadians(180 + i * 18);
            double x1 = cx + Math.cos(ang) * (r - 20 * s);
            double y1 = cy - Math.sin(ang) * (r - 20 * s);
            double x2 = cx + Math.cos(ang) * (r - 52 * s);
            double y2 = cy - Math.sin(ang) * (r - 52 * s);
            g.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }

        // 指针(指向右上方 ~45°)
        double needleAng = Math.toRadians(45);
        int nx = cx + (int) (Math.cos(needleAng) * (r - 70 * s));
        int ny = cy - (int) (Math.sin(needleAng) * (r - 70 * s));
        g.setStroke(new BasicStroke((int) (28 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.drawLine(cx, cy, nx, ny);

        // 中心轴
        g.setColor(new Color(0x1c46c0));
        g.fillOval(cx - (int) (36 * s), cy - (int) (36 * s), (int) (72 * s), (int) (72 * s));
        g.setColor(Color.WHITE);
        g.fillOval(cx - (int) (20 * s), cy - (int) (20 * s), (int) (40 * s), (int) (40 * s));

        g.dispose();
        return img;
    }

    static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /** 写 Windows ICO:容器头 + 目录项 + PNG 数据(现代 Windows 支持 PNG 压缩条目) */
    static void writeIco(Path out, BufferedImage master, int[] sizes) throws IOException {
        ByteArrayOutputStream[] pngs = new ByteArrayOutputStream[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            pngs[i] = new ByteArrayOutputStream();
            ImageIO.write(scale(master, sizes[i]), "png", pngs[i]);
        }
        int header = 6;
        int dir = 16;
        int offset = header + dir * sizes.length;
        ByteArrayOutputStream ico = new ByteArrayOutputStream();
        ico.write(new byte[]{0, 0, 1, 0, (byte) sizes.length, 0}); // reserved, type=1, count
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            byte[] p = pngs[i].toByteArray();
            ico.write(s >= 256 ? 0 : s);          // width(0 表示 256)
            ico.write(s >= 256 ? 0 : s);          // height
            ico.write(0);                          // color palette count
            ico.write(0);                          // reserved
            ico.write(new byte[]{1, 0});           // planes
            ico.write(new byte[]{32, 0});          // bits per pixel
            ico.write(intLE(p.length));            // bytes in resource
            ico.write(intLE(offset));              // image offset
            offset += p.length;
        }
        for (ByteArrayOutputStream p : pngs) ico.write(p.toByteArray());
        Files.write(out, ico.toByteArray());
    }

    static byte[] intLE(int v) {
        return new byte[]{(byte) (v & 0xff), (byte) ((v >> 8) & 0xff), (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)};
    }
}
