package io.github.prismpx.webpbridge;

import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value=60, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class MalformedInputTest {
    private static void decodeOrReject(byte[] bytes) {
        try {
            var image=WebP.decode(bytes,4096);
            assertTrue(image.getWidth()>0 && image.getHeight()>0);
            assertTrue((long)image.getWidth()*image.getHeight()<=4096);
        } catch (IllegalArgumentException expected) {
            // Mutations can remain valid. Only this documented rejection is acceptable.
        }
    }
    @Test void everyTruncatedPrefixAndSeededMutations() {
        var source=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        var random=new Random(20261101);
        for(int y=0;y<32;y++) for(int x=0;x<32;x++) source.setRGB(x,y,random.nextInt());
        int count=0;
        for(boolean lossless:new boolean[]{false,true}) {
            byte[] encoded=lossless ? WebP.encodeLossless(source) : WebP.encode(source,80);
            for(int n=0;n<encoded.length;n++) { decodeOrReject(Arrays.copyOf(encoded,n)); count++; }
            for(int i=0;i<2000;i++) {
                byte[] mutated=encoded.clone();
                int edits=1+random.nextInt(8);
                for(int j=0;j<edits;j++) mutated[random.nextInt(mutated.length)]=(byte)random.nextInt(256);
                decodeOrReject(mutated); count++;
            }
            for(int offset:new int[]{0,4,8,12,16}) {
                byte[] malformed=encoded.clone();
                Arrays.fill(malformed,offset,Math.min(offset+4,malformed.length),(byte)255);
                decodeOrReject(malformed); count++;
            }
        }
        for(int i=0;i<1000;i++) { byte[] randomBytes=new byte[random.nextInt(512)]; random.nextBytes(randomBytes); decodeOrReject(randomBytes); count++; }
        System.out.println("MALFORMED cases="+count+" seed=20261101 limit=4096 pixels");
    }
    @Test void rejectsOversizedExtendedHeaderBeforeDecode() {
        // VP8X requests the maximum 24-bit canvas in both dimensions.
        byte[] header={'R','I','F','F',22,0,0,0,'W','E','B','P','V','P','8','X',10,0,0,0,0,0,0,0,-1,-1,-1,-1,-1,-1};
        assertThrows(IllegalArgumentException.class,()->WebP.decode(header,4096));
    }
    @Test void dimensionAndQualityBoundaries() {
        for(int width:new int[]{1,16383}) {
            var original=new BufferedImage(width,1,BufferedImage.TYPE_INT_ARGB);
            original.setRGB(width-1,0,0x80123456);
            var restored=WebP.decode(WebP.encodeLossless(original));
            assertEquals(width,restored.getWidth());
            assertEquals(0x80123456,restored.getRGB(width-1,0));
        }
        assertThrows(IllegalArgumentException.class,()->WebP.encodeLossless(new BufferedImage(16384,1,BufferedImage.TYPE_INT_RGB)));
        assertThrows(IllegalArgumentException.class,()->WebP.encodeLossless(new BufferedImage(1,16384,BufferedImage.TYPE_INT_RGB)));
        var one=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);
        for(float q:new float[]{0,100}) assertEquals(1,WebP.decode(WebP.encode(one,q)).getWidth());
        assertThrows(IllegalArgumentException.class,()->WebP.decode(new byte[0],Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->WebP.decode(new byte[0],-1));
    }
}
