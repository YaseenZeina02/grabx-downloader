import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Raster export of chromium/icons/grabx.svg; Chrome requires PNG manifest icons. */
class GenerateIcons {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (int size : new int[]{16, 32, 48, 128}) {
            int scale = 4;
            BufferedImage image = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(size * scale / 128.0, size * scale / 128.0);
            g.setPaint(new GradientPaint(4, 4, Color.decode("#26324a"), 124, 124, Color.decode("#101622")));
            var panel = new RoundRectangle2D.Double(4, 4, 120, 120, 56, 56);
            g.fill(panel);
            g.setColor(Color.decode("#52668e"));
            g.setStroke(new BasicStroke(2));
            g.draw(panel);
            g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.decode("#e8ecf3"));
            Path2D letterG = new Path2D.Double();
            letterG.moveTo(58, 44);
            letterG.curveTo(53, 39, 49, 38, 43, 38);
            letterG.curveTo(30, 38, 23, 48, 23, 64);
            letterG.curveTo(23, 80, 30, 90, 43, 90);
            letterG.curveTo(49, 90, 54, 88, 58, 84);
            letterG.lineTo(58, 66); letterG.lineTo(45, 66);
            g.draw(letterG);
            g.setColor(Color.decode("#9fb6ff"));
            Path2D letterX = new Path2D.Double();
            letterX.moveTo(77, 42); letterX.lineTo(105, 86);
            letterX.moveTo(105, 42); letterX.lineTo(77, 86);
            g.draw(letterX); g.dispose();
            BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, 0, 0, size, size, null); g.dispose();
            ImageIO.write(small, "png", output.resolve("grabx-" + size + ".png").toFile());
        }
    }
}
