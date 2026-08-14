import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Packs the 321 downloaded shard icons into one texture atlas.
 *
 * <p>Individually, each icon is a separate Minecraft texture that has to be
 * registered and loaded on demand. In practice a good number of them never
 * resolved and drew as the magenta missing-texture check - reliably so once
 * enough rows were on screen at once. One atlas is one texture: it loads once,
 * cannot half-load, and batches into far fewer draw calls.
 *
 * <p>Run after scripts/fetch_shard_icons.py:
 * <pre>
 *   javac -d out mod/tools/BuildAtlas.java
 *   java -cp out BuildAtlas
 * </pre>
 */
public class BuildAtlas {

    static final int CELL = 32;

    public static void main(String[] args) throws IOException {
        Path root = Path.of("C:\\Users\\thesh\\Downloads\\shardfuse");
        Path srcDir = root.resolve("mod/src/main/resources/assets/squidutils/textures/shard");
        Path outPng = root.resolve("mod/src/main/resources/assets/squidutils/textures/shard_atlas.png");
        Path outIdx = root.resolve("mod/src/main/resources/assets/squidutils/shard-atlas.json");

        File[] files = srcDir.toFile().listFiles((d, n) -> n.endsWith(".png"));
        if (files == null || files.length == 0) {
            System.out.println("no icons found in " + srcDir);
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        int cols = (int) Math.ceil(Math.sqrt(files.length));
        int rows = (int) Math.ceil(files.length / (double) cols);
        BufferedImage atlas = new BufferedImage(cols * CELL, rows * CELL,
                BufferedImage.TYPE_INT_ARGB);
        var g = atlas.createGraphics();

        List<String> names = new ArrayList<>(files.length);
        int bad = 0;
        for (int i = 0; i < files.length; i++) {
            BufferedImage img = ImageIO.read(files[i]);
            String name = files[i].getName().replaceAll("\\.png$", "");
            if (img == null) {
                bad++;
                names.add(name);       // keep the slot so indices stay aligned
                continue;
            }
            int x = (i % cols) * CELL;
            int y = (i / cols) * CELL;
            g.drawImage(img, x, y, CELL, CELL, null);
            names.add(name);
        }
        g.dispose();

        Files.createDirectories(outPng.getParent());
        ImageIO.write(atlas, "PNG", outPng.toFile());

        // Order is the index: cell (i % cols, i / cols) at CELL pixels each.
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cell\":").append(CELL)
          .append(",\"cols\":").append(cols)
          .append(",\"width\":").append(atlas.getWidth())
          .append(",\"height\":").append(atlas.getHeight())
          .append(",\"names\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(names.get(i)).append('"');
        }
        sb.append("]}");
        Files.writeString(outIdx, sb.toString(), StandardCharsets.UTF_8);

        System.out.printf("atlas %dx%d (%d cols x %d rows) from %d icons, %d unreadable%n",
                atlas.getWidth(), atlas.getHeight(), cols, rows, files.length, bad);
        System.out.printf("  %s  (%,d bytes)%n", outPng, Files.size(outPng));
        System.out.printf("  %s%n", outIdx);
    }
}
