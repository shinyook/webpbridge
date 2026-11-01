package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PixelPathTest {
    private static BufferedImage random(int type, int w, int h, long seed) {
        var image=new BufferedImage(w,h,type);
        var random=new Random(seed);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setRGB(x,y,random.nextInt());
        return image;
    }
    /** Same pixels through the generic getRGB path (a type outside the packed-int fast path). */
    private static BufferedImage generic(BufferedImage source) {
        var copy=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_4BYTE_ABGR);
        copy.setRGB(0,0,source.getWidth(),source.getHeight(),
            source.getRGB(0,0,source.getWidth(),source.getHeight(),null,0,source.getWidth()),0,source.getWidth());
        return copy;
    }
    @Test void packedIntFastPathMatchesGenericPathByteForByte() {
        for(int type:new int[]{BufferedImage.TYPE_INT_ARGB,BufferedImage.TYPE_INT_RGB}) {
            var full=random(type,67,41,type);
            for(var image:new BufferedImage[]{full,full.getSubimage(5,3,40,30),full.getSubimage(66,40,1,1)}) {
                var reference=generic(image);
                assertArrayEquals(WebP.encodeLossless(reference),WebP.encodeLossless(image));
                assertArrayEquals(WebP.encode(reference,80),WebP.encode(image,80));
                ImageTypesTest.assertPixels(image,WebP.decode(WebP.encodeLossless(image)),true);
            }
        }
    }
    @Test void rgbIgnoresUndefinedHighByteInRaster() {
        var image=new BufferedImage(8,4,BufferedImage.TYPE_INT_RGB);
        int[] data=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
        for(int i=0;i<data.length;i++) data[i]=(i*0x01020304)|0x12000000;
        var decoded=WebP.decode(WebP.encodeLossless(image));
        assertEquals(BufferedImage.TYPE_INT_RGB,decoded.getType());
        for(int i=0;i<data.length;i++) assertEquals(0xff000000|(data[i]&0xffffff),decoded.getRGB(i%8,i/8));
    }
    @Test void decodedImageIsIndependentAndWritable() {
        var source=random(BufferedImage.TYPE_INT_ARGB,16,9,7);
        byte[] encoded=WebP.encodeLossless(source);
        var first=WebP.decode(encoded); var second=WebP.decode(encoded);
        first.setRGB(0,0,0x01020304);
        assertEquals(0x01020304,first.getRGB(0,0));
        assertEquals(source.getRGB(0,0),second.getRGB(0,0));
        assertArrayEquals(WebP.encodeLossless(source),WebP.encodeLossless(second));
    }
}
