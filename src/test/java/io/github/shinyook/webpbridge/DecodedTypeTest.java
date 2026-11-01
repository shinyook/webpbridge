package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecodedTypeTest {
    private static BufferedImage opaque() {
        var image=new BufferedImage(37,29,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<29;y++) for(int x=0;x<37;x++) image.setRGB(x,y,(x*7<<16)|(y*11<<8)|((x+y)*3&255));
        return image;
    }
    @Test void opaqueLossyDecodesAsRgbAndWritesJpeg() throws Exception {
        byte[] encoded=WebP.encode(opaque(),80);
        assertFalse(WebP.inspect(encoded).hasAlpha());
        var decoded=WebP.decode(encoded);
        assertEquals(BufferedImage.TYPE_INT_RGB,decoded.getType());
        var jpeg=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(decoded,"jpg",jpeg));
        var restored=ImageIO.read(new ByteArrayInputStream(jpeg.toByteArray()));
        assertEquals(37,restored.getWidth()); assertEquals(29,restored.getHeight());
    }
    @Test void opaqueLosslessDecodesAsRgbWithExactPixels() {
        var source=opaque();
        var decoded=WebP.decode(WebP.encodeLossless(source));
        assertEquals(BufferedImage.TYPE_INT_RGB,decoded.getType());
        ImageTypesTest.assertPixels(source,decoded,true);
    }
    @Test void transparentInputKeepsArgbOnEveryOverload() throws Exception {
        var source=ImageTypesTest.sample(BufferedImage.TYPE_INT_ARGB);
        byte[] lossless=WebP.encodeLossless(source);
        assertTrue(WebP.inspect(lossless).hasAlpha());
        assertEquals(BufferedImage.TYPE_INT_ARGB,WebP.decode(lossless).getType());
        assertEquals(BufferedImage.TYPE_INT_ARGB,WebP.decode(lossless,4096).getType());
        assertEquals(BufferedImage.TYPE_INT_ARGB,WebP.decode(lossless,WebPOptions.DEFAULT).getType());
        assertEquals(BufferedImage.TYPE_INT_ARGB,WebP.read(new ByteArrayInputStream(lossless)).getType());
        assertEquals(BufferedImage.TYPE_INT_ARGB,WebP.decode(WebP.encode(source,80)).getType());
    }
    @Test void opaqueTypeAppliesToEveryOverload() throws Exception {
        byte[] encoded=WebP.encode(opaque(),80);
        assertEquals(BufferedImage.TYPE_INT_RGB,WebP.decode(encoded,4096).getType());
        assertEquals(BufferedImage.TYPE_INT_RGB,WebP.decode(encoded,WebPOptions.DEFAULT).getType());
        assertEquals(BufferedImage.TYPE_INT_RGB,WebP.read(new ByteArrayInputStream(encoded)).getType());
    }
}
