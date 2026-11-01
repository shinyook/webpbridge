package io.github.shinyook.webpbridge.benchmarks;

import io.github.shinyook.webpbridge.WebP;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.*;
import javax.imageio.spi.*;
import javax.imageio.stream.*;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
@Fork(value=2,jvmArgsAppend={"-Xms256m","-Xmx1g"})
@Threads(1)
@State(Scope.Thread)
public class CodecBenchmark {
    @Param({"ui256","texture1024"}) public String dataset;
    @Param({"lossless","lossy80"}) public String compression;
    BufferedImage image;
    byte[] encoded;
    ImageReaderSpi nativeReader;
    ImageReaderSpi javaReader;
    ImageWriterSpi nativeWriter;

    @Setup(Level.Trial) public void setup() throws Exception {
        int size=dataset.equals("ui256")?256:1024;
        image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        var random=new Random(20261101);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
            int rgb=dataset.equals("ui256") ? ((x/16*17)<<16)|((y/16*17)<<8)|((x/32+y/32)%2*255)
                : random.nextInt(1<<24);
            image.setRGB(x,y,0xff000000|rgb);
        }
        nativeReader=(ImageReaderSpi)Class.forName("com.luciad.imageio.webp.WebPImageReaderSpi").getConstructor().newInstance();
        nativeWriter=(ImageWriterSpi)Class.forName("com.luciad.imageio.webp.WebPImageWriterSpi").getConstructor().newInstance();
        javaReader=(ImageReaderSpi)Class.forName("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi").getConstructor().newInstance();
        encoded=encodeJni();
        check(decodeJni()); check(decodeImageio()); check(decodeTwelveMonkeys());
        byte[] other=encodeImageio();
        check(WebP.decode(other));
        if(!compression.equals("lossless")) {
            // Check that matched quality settings produce the same decoded image.
            int[] a=decodeJni().getRGB(0,0,size,size,null,0,size);
            int[] b=WebP.decode(other).getRGB(0,0,size,size,null,0,size);
            if(!Arrays.equals(a,b)) throw new IllegalStateException("Encoder outputs differ; review settings before comparing");
        }
    }
    private void check(BufferedImage decoded) {
        int size=image.getWidth();
        if(decoded.getWidth()!=size || decoded.getHeight()!=size) throw new IllegalStateException("Dimension mismatch");
        if(compression.equals("lossless") && !Arrays.equals(image.getRGB(0,0,size,size,null,0,size),decoded.getRGB(0,0,size,size,null,0,size)))
            throw new IllegalStateException("Lossless pixel mismatch");
    }
    @Benchmark public byte[] encodeJni() {
        return compression.equals("lossless")?WebP.encodeLossless(image):WebP.encode(image,80);
    }
    @Benchmark public byte[] encodeImageio() throws Exception {
        ImageWriter writer=nativeWriter.createWriterInstance();
        try(var bytes=new ByteArrayOutputStream(); var out=new MemoryCacheImageOutputStream(bytes)) {
            WebPWriteParam param=(WebPWriteParam)writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(compression.equals("lossless")?"Lossless":"Lossy");
            param.setCompressionQuality(compression.equals("lossless")?0.75f:0.8f);
            param.setMethod(4); param.setExact(true); param.setThreadLevel(0); param.setUseSharpYUV(false);
            writer.setOutput(out); writer.write(null,new IIOImage(image,null,null),param); out.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
    @Benchmark public BufferedImage decodeJni() { return WebP.decode(encoded); }
    @Benchmark public BufferedImage decodeImageio() throws Exception { return decode(nativeReader); }
    @Benchmark public BufferedImage decodeTwelveMonkeys() throws Exception { return decode(javaReader); }
    private BufferedImage decode(ImageReaderSpi provider) throws Exception {
        ImageReader reader=provider.createReaderInstance();
        try(var in=new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            reader.setInput(in); return reader.read(0);
        } finally { reader.dispose(); }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("dataset,compression,jniBytes,imageioBytes,jniSha256,imageioSha256");
        for(String dataset:List.of("ui256","texture1024")) for(String compression:List.of("lossless","lossy80")) {
            var state=new CodecBenchmark(); state.dataset=dataset; state.compression=compression; state.setup();
            byte[] a=state.encoded,b=state.encodeImageio();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            System.out.printf("%s,%s,%d,%d,%s,%s%n",dataset,compression,a.length,b.length,
                HexFormat.of().formatHex(digest.digest(a)),HexFormat.of().formatHex(digest.digest(b)));
        }
    }
}
