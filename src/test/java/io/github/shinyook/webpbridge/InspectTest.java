package io.github.shinyook.webpbridge;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InspectTest {
    @Test void reportsHeaderWithoutPixelLimitEnforcement() {
        var source=ImageTypesTest.sample(BufferedImage.TYPE_INT_ARGB);
        byte[] encoded=WebP.encodeLossless(source);
        var info=WebP.inspect(encoded,WebPOptions.builder().maxPixels(1).build());
        assertEquals(37,info.width()); assertEquals(29,info.height()); assertEquals(1073,info.pixelCount());
        assertTrue(info.hasAlpha()); assertFalse(info.animated()); assertEquals(WebPInfo.Format.LOSSLESS,info.format());
        var opaque=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);
        info=WebP.inspect(WebP.encode(opaque,80));
        assertFalse(info.hasAlpha()); assertEquals(WebPInfo.Format.LOSSY,info.format());
    }
    @Test void identifiesAnimationWithoutTryingToDecodeIt() {
        byte[] animation={'R','I','F','F',22,0,0,0,'W','E','B','P','V','P','8','X',10,0,0,0,2,0,0,0,0,0,0,0,0,0};
        var info=WebP.inspect(animation);
        assertTrue(info.animated()); assertEquals(1,info.width());
        assertThrows(IllegalArgumentException.class,()->WebP.decode(animation));
    }
    @Test void rejectsMalformedContainersAndByteLimit() {
        byte[] valid=WebP.encodeLossless(new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB));
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(new byte[0]));
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(Arrays.copyOf(valid,valid.length-1)));
        byte[] bad=valid.clone(); bad[0]='X';
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(bad));
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(valid,WebPOptions.builder().maxInputBytes(valid.length-1).build()));
    }
}
