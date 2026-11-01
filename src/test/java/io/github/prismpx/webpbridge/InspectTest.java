package io.github.prismpx.webpbridge;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
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
        byte[] bad=valid.clone(); bad[8]='X';
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(bad));
        byte[] notWebP=valid.clone(); notWebP[0]='X';
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(notWebP));
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(valid,WebPOptions.builder().maxInputBytes(valid.length-1).build()));
    }
    @Test void acceptsTrailingBytesLikeDecode() {
        var source=ImageTypesTest.sample(BufferedImage.TYPE_INT_ARGB);
        for(byte[] encoded:new byte[][]{WebP.encode(source,80),WebP.encodeLossless(source)}) {
            byte[] trailing=Arrays.copyOf(encoded,encoded.length+17);
            var decoded=WebP.decode(trailing);
            var info=WebP.inspect(trailing);
            assertEquals(decoded.getWidth(),info.width()); assertEquals(decoded.getHeight(),info.height());
            assertEquals(WebP.inspect(encoded),info);
        }
    }
    @Test void everyDecodableMutationIsInspectableWithSameDimensions() {
        var random=new Random(20261101);
        var source=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++) for(int x=0;x<32;x++) source.setRGB(x,y,random.nextInt());
        int decodable=0, cases=0;
        for(boolean lossless:new boolean[]{false,true}) {
            byte[] encoded=lossless ? WebP.encodeLossless(source) : WebP.encode(source,80);
            var inputs=new ArrayList<byte[]>();
            for(int n=0;n<=encoded.length+8;n++) inputs.add(Arrays.copyOf(encoded,n));
            for(int i=0;i<2000;i++) {
                byte[] mutated=encoded.clone();
                int edits=1+random.nextInt(8);
                for(int j=0;j<edits;j++) mutated[random.nextInt(mutated.length)]=(byte)random.nextInt(256);
                inputs.add(mutated);
            }
            for(byte[] input:inputs) {
                cases++;
                BufferedImage decoded;
                try { decoded=WebP.decode(input,1<<20); } catch(IllegalArgumentException rejected) { continue; }
                decodable++;
                var info=assertDoesNotThrow(()->WebP.inspect(input),"decode accepted input that inspect rejected");
                assertEquals(decoded.getWidth(),info.width()); assertEquals(decoded.getHeight(),info.height());
                assertEquals(decoded.getType()==BufferedImage.TYPE_INT_ARGB,info.hasAlpha());
            }
        }
        assertTrue(decodable>0);
        System.out.println("INSPECT_CONSISTENCY cases="+cases+" decodable="+decodable+" seed=20261101");
    }
}
