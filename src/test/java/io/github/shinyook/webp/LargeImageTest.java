package io.github.shinyook.webp;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LargeImageTest {
    static BufferedImage image(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++)
            image.setRGB(x,y,((x+y)%256<<24) | ((x/16)%256<<16) | ((y/16)%256<<8) | ((x+y)/32)%256);
        return image;
    }
    static void verify(BufferedImage source, boolean lossless) {
        long start=System.nanoTime();
        byte[] encoded=lossless ? WebP.encodeLossless(source) : WebP.encode(source,80);
        if ((long)source.getWidth()*source.getHeight()>16_000_000)
            assertThrows(IllegalArgumentException.class,()->WebP.decode(encoded));
        var result=WebP.decode(encoded,source.getWidth()*source.getHeight());
        assertEquals(source.getWidth(),result.getWidth());
        assertEquals(source.getHeight(),result.getHeight());
        // Row comparisons avoid allocating another full-image pixel array.
        for(int y=0;y<source.getHeight();y++) {
            int[] expected=source.getRGB(0,y,source.getWidth(),1,null,0,source.getWidth());
            int[] actual=result.getRGB(0,y,result.getWidth(),1,null,0,result.getWidth());
            if(lossless) assertArrayEquals(expected,actual,"row "+y);
            else for(int x=0;x<expected.length;x++)
                if((expected[x]>>>24)!=(actual[x]>>>24)) fail("alpha mismatch at "+x+","+y);
        }
        System.out.printf("LOAD width=%d height=%d lossless=%s bytes=%d elapsedMs=%d%n",
            source.getWidth(),source.getHeight(),lossless,encoded.length,(System.nanoTime()-start)/1_000_000);
    }
    @Test void fourKLossyAndLosslessWithExplicitDecodeLimit() {
        var source=image(4096,4096);
        verify(source,true); verify(source,false);
    }
    @Test void twoKHighEntropyRoundTrips() {
        var source=new BufferedImage(2048,2048,BufferedImage.TYPE_INT_ARGB);
        var random=new java.util.Random(20261101);
        int[] row=new int[2048];
        for(int y=0;y<2048;y++) {
            for(int x=0;x<row.length;x++) row[x]=random.nextInt();
            source.setRGB(0,y,2048,1,row,0,2048);
        }
        verify(source,true); verify(source,false);
    }
    @Test void twoConcurrentTwoKRoundTrips() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) futures.add(pool.submit(()->verify(image(2048,2048),true)));
            for(var future:futures) future.get(120,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
}
