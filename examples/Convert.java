import io.github.shinyook.webpbridge.WebP;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/** Usage: java -cp target/webpbridge-0.1.0-SNAPSHOT.jar examples/Convert.java input.png output.webp */
class Convert {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected input.png output.webp");
        var image = ImageIO.read(Path.of(args[0]).toFile());
        if (image == null) throw new IllegalArgumentException("Unsupported input image");
        Files.write(Path.of(args[1]), WebP.encodeLossless(image));
    }
}
