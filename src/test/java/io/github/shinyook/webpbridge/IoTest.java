package io.github.shinyook.webpbridge;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class IoTest {
    @TempDir Path temp;
    private final WebPOptions lossless=WebPOptions.builder().lossless(true).build();
    private BufferedImage image() { return ImageTypesTest.sample(BufferedImage.TYPE_INT_ARGB); }
    static class Input extends ByteArrayInputStream {
        boolean closed;
        Input(byte[] bytes) { super(bytes); }
        @Override public void close() { closed=true; }
    }
    static class Output extends ByteArrayOutputStream {
        boolean closed,flushed;
        @Override public void close() { closed=true; }
        @Override public void flush() { flushed=true; }
    }
    @Test void roundTripsFilesAndStreamsWithoutTakingOwnership() throws Exception {
        Path file=temp.resolve("image with spaces.webp");
        WebP.write(image(),file,lossless);
        ImageTypesTest.assertPixels(image(),WebP.read(file),true);
        assertEquals(37,WebP.inspect(file).width());
        var out=new Output(); WebP.write(image(),out,lossless);
        assertFalse(out.closed); assertFalse(out.flushed);
        var in=new Input(out.toByteArray()); ImageTypesTest.assertPixels(image(),WebP.read(in),true); assertFalse(in.closed);
        in=new Input(out.toByteArray()); assertEquals(37,WebP.inspect(in).width()); assertFalse(in.closed);
    }
    @Test void boundsUnknownLengthStreamsAndHandlesZeroBulkReads() throws Exception {
        byte[] bytes=WebP.encode(image(),lossless);
        var exact=WebPOptions.builder().maxInputBytes(bytes.length).build();
        assertNotNull(WebP.read(new ByteArrayInputStream(bytes),exact));
        InputStream zeroReads=new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int off,int len) { return 0; }
        };
        assertNotNull(WebP.read(zeroReads,exact));
        var small=WebPOptions.builder().maxInputBytes(8).build();
        class Endless extends InputStream {
            int count;
            public int read() { count++; return 0; }
        }
        var endless=new Endless();
        assertThrows(IllegalArgumentException.class,()->WebP.read(endless,small));
        assertEquals(9,endless.count);
        var limited=new Input(bytes);
        assertThrows(IllegalArgumentException.class,()->WebP.inspect(limited,small)); assertFalse(limited.closed);
    }
    @Test void preservesExistingOutputsAndDoesNotTouchOutputOnValidationFailure() throws Exception {
        Path file=temp.resolve("existing"); Files.writeString(file,"keep");
        assertThrows(FileAlreadyExistsException.class,()->WebP.write(image(),file,lossless));
        assertEquals("keep",Files.readString(file));
        var out=new Output();
        var tiny=WebPOptions.builder().maxPixels(1).build();
        assertThrows(IllegalArgumentException.class,()->WebP.write(image(),out,tiny)); assertEquals(0,out.size());
        Path absent=temp.resolve("not-created");
        assertThrows(IllegalArgumentException.class,()->WebP.write(image(),absent,tiny)); assertFalse(Files.exists(absent));
    }
    @Test void propagatesIoFailuresWithoutClosingCallerStreams() {
        InputStream broken=new InputStream() { public int read() throws IOException { throw new IOException("read failure"); } };
        assertEquals("read failure",assertThrows(IOException.class,()->WebP.read(broken)).getMessage());
        OutputStream brokenOut=new OutputStream() { public void write(int value) throws IOException { throw new IOException("write failure"); } };
        assertEquals("write failure",assertThrows(IOException.class,()->WebP.write(image(),brokenOut,lossless)).getMessage());
    }
}
