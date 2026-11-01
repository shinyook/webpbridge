package io.github.prismpx.webpbridge;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OptionsTest {
    @Test void rejectsInvalidOptions() {
        for(float q:new float[]{-1,101,Float.NaN,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->WebPOptions.builder().quality(q).build());
        for(int limit:new int[]{0,-1,Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->WebPOptions.builder().maxPixels(limit).build());
            assertThrows(IllegalArgumentException.class,()->WebPOptions.builder().maxInputBytes(limit).build());
        }
    }
    @Test void snapshotsAreIndependent() {
        var builder=WebPOptions.builder().quality(25);
        var first=builder.build(); builder.quality(90).lossless(true);
        assertEquals(25,first.quality()); assertFalse(first.lossless());
        assertEquals(90,builder.build().quality()); assertEquals(80,WebPOptions.DEFAULT.quality());
    }
    @Test void optionLimitsAndLosslessPixels() {
        var source=ImageTypesTest.sample(BufferedImage.TYPE_INT_ARGB);
        int pixels=source.getWidth()*source.getHeight();
        var options=WebPOptions.builder().lossless(true).maxPixels(pixels).build();
        byte[] encoded=WebP.encode(source,options);
        ImageTypesTest.assertPixels(source,WebP.decode(encoded,options),true);
        assertThrows(IllegalArgumentException.class,()->WebP.encode(source,WebPOptions.builder().maxPixels(pixels-1).build()));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(encoded,WebPOptions.builder().maxPixels(pixels-1).build()));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(encoded,WebPOptions.builder().maxInputBytes(encoded.length-1).build()));
        assertNotNull(WebP.decode(encoded,WebPOptions.builder().maxInputBytes(encoded.length).build()));
    }
}
