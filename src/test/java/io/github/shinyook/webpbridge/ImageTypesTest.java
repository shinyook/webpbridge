package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ImageTypesTest {
    static IntStream types() {
        return IntStream.of(BufferedImage.TYPE_INT_RGB,BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,BufferedImage.TYPE_4BYTE_ABGR_PRE,
            BufferedImage.TYPE_BYTE_GRAY,BufferedImage.TYPE_USHORT_GRAY,
            BufferedImage.TYPE_USHORT_565_RGB,BufferedImage.TYPE_BYTE_BINARY,BufferedImage.TYPE_BYTE_INDEXED);
    }
    static BufferedImage sample(int type) {
        var image=new BufferedImage(37,29,type);
        for(int y=0;y<29;y++) for(int x=0;x<37;x++)
            image.setRGB(x,y,(((x+y)*17&255)<<24) | ((x*7&255)<<16) | ((y*11&255)<<8) | ((x+y)*3&255));
        return image;
    }
    static void assertPixels(BufferedImage expected, BufferedImage actual, boolean exact) {
        assertEquals(expected.getWidth(),actual.getWidth()); assertEquals(expected.getHeight(),actual.getHeight());
        int[] a=expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth());
        int[] b=actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth());
        if(exact) assertArrayEquals(a,b);
        else for(int i=0;i<a.length;i++) if((a[i]>>>24)!=(b[i]>>>24)) fail("alpha mismatch at pixel "+i);
    }
    @ParameterizedTest @MethodSource("types")
    void normalizedPixelsSurviveLosslessRoundTrip(int type) {
        var image=sample(type); assertPixels(image,WebP.decode(WebP.encodeLossless(image)),true);
    }
    @ParameterizedTest @MethodSource("types")
    void lossyPreservesDimensionsAndAlpha(int type) {
        var image=sample(type); assertPixels(image,WebP.decode(WebP.encode(image,80)),false);
    }
    @Test void subimageWithParentStrideAndOffset() {
        var image=sample(BufferedImage.TYPE_4BYTE_ABGR).getSubimage(3,5,23,17);
        assertPixels(image,WebP.decode(WebP.encodeLossless(image)),true);
    }
}
