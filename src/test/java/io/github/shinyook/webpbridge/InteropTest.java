package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("interop")
@Timeout(value=120, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class InteropTest {
    @TempDir Path temp;
    private void run(String... args) throws Exception {
        Path log=temp.resolve("tool.log");
        Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"codec process timed out");
            assertEquals(0,process.exitValue(),Files.readString(log));
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    @Test void cwebpToJava() throws Exception {
        for(int type:new int[]{BufferedImage.TYPE_INT_ARGB,BufferedImage.TYPE_INT_RGB,BufferedImage.TYPE_BYTE_GRAY}) {
            var source=ImageTypesTest.sample(type);
            Path png=temp.resolve("source with spaces.png"), webp=temp.resolve("external.webp");
            // Canonical sRGB PNG avoids grayscale transfer-function differences
            // between the Java PNG reader and cwebp's PNG reader.
            var normalized=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
            normalized.setRGB(0,0,source.getWidth(),source.getHeight(),
                source.getRGB(0,0,source.getWidth(),source.getHeight(),null,0,source.getWidth()),0,source.getWidth());
            assertTrue(ImageIO.write(normalized,"png",png.toFile()));
            run("cwebp","-lossless","-exact",png.toString(),"-o",webp.toString());
            ImageTypesTest.assertPixels(normalized,WebP.decode(Files.readAllBytes(webp)),true);
            run("cwebp","-q","80",png.toString(),"-o",webp.toString());
            ImageTypesTest.assertPixels(normalized,WebP.decode(Files.readAllBytes(webp)),false);
        }
    }
    @Test void grayscalePngComparedAgainstIndependentDecoder() throws Exception {
        var source=ImageTypesTest.sample(BufferedImage.TYPE_BYTE_GRAY);
        Path png=temp.resolve("gray.png"), webp=temp.resolve("gray.webp"), reference=temp.resolve("reference.png");
        assertTrue(ImageIO.write(source,"png",png.toFile()));
        run("cwebp","-lossless","-exact",png.toString(),"-o",webp.toString());
        run("dwebp",webp.toString(),"-o",reference.toString());
        ImageTypesTest.assertPixels(ImageIO.read(reference.toFile()),WebP.decode(Files.readAllBytes(webp)),true);
    }
    @Test void javaToDwebp() throws Exception {
        for(int type:new int[]{BufferedImage.TYPE_INT_ARGB,BufferedImage.TYPE_INT_RGB,BufferedImage.TYPE_BYTE_GRAY}) {
            var source=ImageTypesTest.sample(type);
            Path webp=temp.resolve("java with spaces.webp"), png=temp.resolve("decoded.png");
            for(boolean lossless:new boolean[]{true,false}) {
                Files.write(webp,lossless?WebP.encodeLossless(source):WebP.encode(source,80));
                run("dwebp",webp.toString(),"-o",png.toString());
                ImageTypesTest.assertPixels(source,ImageIO.read(png.toFile()),lossless);
            }
        }
    }
}
