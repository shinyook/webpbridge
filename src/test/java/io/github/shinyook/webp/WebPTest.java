package io.github.shinyook.webp;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebPTest {
    private BufferedImage sample() {
        BufferedImage image = new BufferedImage(19, 13, BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<13; y++) for (int x=0; x<19; x++)
            image.setRGB(x, y, ((x*13 & 255)<<24) | ((y*19 & 255)<<16) | (x*11<<8) | y*17);
        return image;
    }
    @Test void losslessPreservesAllPixelsIncludingTransparentRgb() {
        BufferedImage original=sample(), decoded=WebP.decode(WebP.encodeLossless(original));
        assertEquals(original.getWidth(), decoded.getWidth());
        assertEquals(original.getHeight(), decoded.getHeight());
        assertArrayEquals(original.getRGB(0,0,19,13,null,0,19), decoded.getRGB(0,0,19,13,null,0,19));
    }
    @Test void lossyHasWebpContainerAndPreservesAlpha() {
        BufferedImage original=sample(); byte[] encoded=WebP.encode(original,80);
        assertEquals("RIFF", new String(encoded,0,4,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(encoded,8,4,java.nio.charset.StandardCharsets.US_ASCII));
        BufferedImage decoded=WebP.decode(encoded);
        for (int y=0;y<13;y++) for (int x=0;x<19;x++) assertEquals(original.getRGB(x,y)>>>24,decoded.getRGB(x,y)>>>24);
    }
    @Test void validatesInputsAndDecodeLimits() {
        for (float quality : new float[]{-1,101,Float.NaN,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->WebP.encode(sample(),quality));
        assertThrows(NullPointerException.class,()->WebP.encodeLossless(null));
        assertThrows(NullPointerException.class,()->WebP.decode(null));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(new byte[]{1,2,3}));
        byte[] valid=WebP.encodeLossless(sample());
        assertThrows(IllegalArgumentException.class,()->WebP.decode(valid,10));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(valid,0));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(Arrays.copyOf(valid,valid.length/2)));
    }
    @Test void decodesIndependentCwebpFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/reference.webp")) {
            BufferedImage decoded = WebP.decode(in.readAllBytes());
            assertEquals(2, decoded.getWidth()); assertEquals(1, decoded.getHeight());
            assertEquals(0xffff0000, decoded.getRGB(0,0));
            assertEquals(0x8000ff00, decoded.getRGB(1,0));
        }
    }
    @Test void rejectsAnimatedHeader() {
        byte[] animated = new byte[]{'R','I','F','F',22,0,0,0,'W','E','B','P','V','P','8','X',10,0,0,0,2,0,0,0,0,0,0,0,0,0};
        assertThrows(IllegalArgumentException.class, () -> WebP.decode(animated));
    }
    @Test void concurrentCalls() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<32;i++) futures.add(pool.submit(()->losslessPreservesAllPixelsIncludingTransparentRgb()));
            for(var future:futures) future.get();
        } finally { pool.shutdownNow(); }
    }
}
