package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@Timeout(value=180, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class MemorySoakTest {
    @Test void repeatedJniCallsAndFailurePaths() {
        var source=LargeImageTest.image(128,128);
        int[] expected=source.getRGB(0,0,128,128,null,0,128);
        for(int i=0;i<2000;i++) {
            byte[] encoded=(i%2==0) ? WebP.encodeLossless(source) : WebP.encode(source,80);
            BufferedImage decoded=WebP.decode(encoded);
            if(i%2==0) assertArrayEquals(expected,decoded.getRGB(0,0,128,128,null,0,128));
            assertThrows(IllegalArgumentException.class,()->WebP.decode(encoded,1));
            assertThrows(IllegalArgumentException.class,()->WebP.decode(new byte[]{'R','I','F','F'}));
        }
        System.out.println("SOAK completed=2000 actual-JVM codec cycles with JNI checks and failure paths");
    }
}
