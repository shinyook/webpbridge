package io.github.prismpx.webpbridge;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** In-memory static WebP encoding and decoding. Methods are safe for concurrent use. */
public final class WebP {
    private WebP() {}
    private static final class Native {
        static { NativeLoader.load(); }
        static native byte[] encode(int[] argb, int width, int height, boolean opaque, float quality, boolean lossless);
        static native int[] decode(byte[] data, int maxPixels, int[] info);
        static native byte[] inspect(byte[] data);
        static native long versions();
    }

    private static final class Diagnostics {
        static final WebPRuntimeInfo INFO=diagnose();
    }
    /** Attempts native initialization and caches a diagnostic snapshot, including load failures.
     * Set webp.jni.path before any native use. Failed initialization requires a fresh class loader/JVM.
     * @return availability, actual versions and bundled resources; load errors are reported as data
     */
    public static WebPRuntimeInfo runtimeInfo() { return Diagnostics.INFO; }
    private static WebPRuntimeInfo diagnose() {
        String platform=NativeLoader.platform();
        var bundled=NativeLoader.bundledPlatforms();
        try {
            long versions=Native.versions();
            return new WebPRuntimeInfo(platform,bundled,true,version((int)(versions>>>32)),version((int)versions),null);
        } catch(LinkageError error) {
            Throwable cause=error;
            while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
            return new WebPRuntimeInfo(platform,bundled,false,null,null,cause.toString());
        }
    }
    private static String version(int packed) {
        return ((packed>>>16)&255)+"."+((packed>>>8)&255)+"."+(packed&255);
    }

    /** Encodes with lossy RGB compression and lossless alpha, quality in [0, 100]. */
    public static byte[] encode(BufferedImage image, float quality) {
        if (!Float.isFinite(quality) || quality < 0 || quality > 100)
            throw new IllegalArgumentException("quality must be finite and between 0 and 100");
        return encodeImage(image, quality, false);
    }

    /** Preserves every RGBA value, including RGB beneath fully transparent pixels. */
    public static byte[] encodeLossless(BufferedImage image) {
        return encodeImage(image, 75, true);
    }

    /** Encodes after checking the source pixel limit, before allocating RGBA copies.
     * @param image source image
     * @param options immutable encoding settings
     * @return encoded WebP bytes
     */
    public static byte[] encode(BufferedImage image, WebPOptions options) {
        Objects.requireNonNull(image, "image"); Objects.requireNonNull(options, "options");
        if ((long)image.getWidth()*image.getHeight()>options.maxPixels())
            throw new IllegalArgumentException("Source image exceeds pixel limit");
        return encodeImage(image,options.quality(),options.lossless());
    }

    /** Decodes with independent encoded-byte and decoded-pixel limits.
     * @param data encoded WebP
     * @param options input limits; encoding quality and lossless flag are ignored
     * @return decoded image
     */
    public static BufferedImage decode(byte[] data, WebPOptions options) {
        checkInput(data,options);
        return decode(data,options.maxPixels());
    }
    /** Inspects a complete RIFF/WebP buffer without decoding pixel data.
     * @param data encoded input, bounded by default byte limit
     * @return header metadata; not proof that the full bitstream decodes
     */
    public static WebPInfo inspect(byte[] data) { return inspect(data,WebPOptions.DEFAULT); }

    /** Inspects header metadata; maxInputBytes applies, maxPixels does not.
     * @param data complete RIFF/WebP input
     * @param options input byte limit; other settings are ignored
     * @return header metadata, including unsupported animation when identified
     */
    public static WebPInfo inspect(byte[] data, WebPOptions options) {
        checkInput(data,options);
        byte[] header=Native.inspect(data);
        WebPInfo.Format format=switch(header[10]) {
            case 1 -> WebPInfo.Format.LOSSY; case 2 -> WebPInfo.Format.LOSSLESS;
            default -> WebPInfo.Format.UNSPECIFIED;
        };
        return new WebPInfo(readInt(header,0),readInt(header,4),header[8]!=0,header[9]!=0,format);
    }

    private static void checkInput(byte[] data, WebPOptions options) {
        Objects.requireNonNull(data,"data"); Objects.requireNonNull(options,"options");
        if(data.length>options.maxInputBytes()) throw new IllegalArgumentException("Encoded input exceeds byte limit");
    }

    private static byte[] encodeImage(BufferedImage image, float quality, boolean lossless) {
        Objects.requireNonNull(image, "image");
        int w = image.getWidth(), h = image.getHeight();
        if (w > 16383 || h > 16383 || (long) w * h > Integer.MAX_VALUE / 4)
            throw new IllegalArgumentException("image exceeds supported dimensions");
        int type = image.getType();
        WritableRaster raster = image.getRaster();
        int[] argb;
        boolean opaque = false;
        if ((type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB)
                && raster.getDataBuffer() instanceof DataBufferInt
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel) {
            // Already packed 0xAARRGGBB (alpha ignored for RGB): copy rows without color conversion.
            argb = (int[]) raster.getDataElements(0, 0, w, h, null);
            opaque = type == BufferedImage.TYPE_INT_RGB;
        } else {
            argb = image.getRGB(0, 0, w, h, null, 0, w);
        }
        return Native.encode(argb, w, h, opaque, quality, lossless);
    }

    /** Decodes a static image, with a default limit of 16 million pixels.
     * Returns TYPE_INT_ARGB when the bitstream has alpha, otherwise TYPE_INT_RGB. */
    public static BufferedImage decode(byte[] data) { return decode(data, 16_000_000); }

    /** Rejects images above maxPixels before allocating the decoded pixel buffer.
     * Returns TYPE_INT_ARGB when the bitstream has alpha, otherwise TYPE_INT_RGB. */
    public static BufferedImage decode(byte[] data, int maxPixels) {
        Objects.requireNonNull(data, "data");
        if (maxPixels <= 0 || maxPixels > (Integer.MAX_VALUE - 8) / 4)
            throw new IllegalArgumentException("invalid maxPixels");
        int[] info = new int[3];
        int[] pixels = Native.decode(data, maxPixels, info);
        int w = info[0], h = info[1];
        boolean alpha = info[2] != 0;
        int[] masks = alpha ? new int[]{0xff0000, 0xff00, 0xff, 0xff000000} : new int[]{0xff0000, 0xff00, 0xff};
        WritableRaster raster = Raster.createPackedRaster(new DataBufferInt(pixels, pixels.length), w, h, w, masks, null);
        ColorModel model = alpha ? ColorModel.getRGBdefault() : new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        BufferedImage image = new BufferedImage(model, raster, false, null);
        return image;
    }
    /** Reads a WebP file using default input limits.
     * @param path input file @return decoded image @throws IOException on file errors */
    public static BufferedImage read(Path path) throws IOException { return read(path,WebPOptions.DEFAULT); }
    /** Reads a bounded WebP file; closes its internally opened stream.
     * @param path input file @param options resource limits @return decoded image @throws IOException on file errors */
    public static BufferedImage read(Path path, WebPOptions options) throws IOException {
        Objects.requireNonNull(options,"options");
        try(InputStream in=Files.newInputStream(path)) { return read(in,options); }
    }
    /** Reads a caller-owned stream using default limits, leaving it open.
     * @param input encoded stream @return decoded image @throws IOException on read errors */
    public static BufferedImage read(InputStream input) throws IOException { return read(input,WebPOptions.DEFAULT); }
    /** Buffers bounded encoded input, then decodes. Does not close the caller's stream.
     * @param input encoded stream @param options resource limits @return decoded image @throws IOException on read errors */
    public static BufferedImage read(InputStream input, WebPOptions options) throws IOException {
        return decode(readBounded(input,options),options);
    }
    /** Inspects a file without decoding pixels, using default input byte limits.
     * @param path input file @return header metadata @throws IOException on file errors */
    public static WebPInfo inspect(Path path) throws IOException { return inspect(path,WebPOptions.DEFAULT); }
    /** Buffers a bounded file for header inspection, closing the internally opened stream.
     * @param path input file @param options input byte limit @return header metadata @throws IOException on file errors */
    public static WebPInfo inspect(Path path, WebPOptions options) throws IOException {
        Objects.requireNonNull(options,"options");
        try(InputStream in=Files.newInputStream(path)) { return inspect(in,options); }
    }
    /** Inspects a caller-owned stream using default input byte limits.
     * @param input encoded stream @return header metadata @throws IOException on read errors */
    public static WebPInfo inspect(InputStream input) throws IOException { return inspect(input,WebPOptions.DEFAULT); }
    /** Buffers bounded encoded input for inspection. Does not close the caller's stream.
     * @param input encoded stream @param options input byte limit @return header metadata @throws IOException on read errors */
    public static WebPInfo inspect(InputStream input, WebPOptions options) throws IOException {
        return inspect(readBounded(input,options),options);
    }
    private static byte[] readBounded(InputStream input, WebPOptions options) throws IOException {
        Objects.requireNonNull(input,"input"); Objects.requireNonNull(options,"options");
        int limit=options.maxInputBytes(), count=0;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(Math.min(8192,limit));
        byte[] buffer=new byte[Math.min(8192,limit)];
        while(true) {
            int n=input.read(buffer,0,(int)Math.min(buffer.length,(long)limit-count+1));
            if(n<0) break;
            if(n==0) {
                int value=input.read();
                if(value<0) break;
                if(count==limit) throw new IllegalArgumentException("Encoded input exceeds byte limit");
                bytes.write(value); count++;
            } else {
                if((long)count+n>limit) throw new IllegalArgumentException("Encoded input exceeds byte limit");
                bytes.write(buffer,0,n); count+=n;
            }
        }
        return bytes.toByteArray();
    }
    /** Encodes with defaults and writes to a caller-owned stream, without closing or flushing it.
     * @param image source image @param output caller-owned output @throws IOException on write errors */
    public static void write(BufferedImage image, OutputStream output) throws IOException { write(image,output,WebPOptions.DEFAULT); }
    /** Finishes encoding before writing. I/O errors can leave partial caller output.
     * @param image source image @param output caller-owned output @param options encoding settings @throws IOException on write errors */
    public static void write(BufferedImage image, OutputStream output, WebPOptions options) throws IOException {
        Objects.requireNonNull(output,"output");
        byte[] encoded=encode(image,options); output.write(encoded);
    }
    /** Creates a new WebP file using defaults; existing paths are never overwritten.
     * @param image source image @param path new destination @throws IOException on file errors or existing path */
    public static void write(BufferedImage image, Path path) throws IOException { write(image,path,WebPOptions.DEFAULT); }
    /** Finishes encoding before creating a new file. A partial new file may remain on I/O failure.
     * @param image source image @param path new destination @param options encoding settings @throws IOException on file errors or existing path */
    public static void write(BufferedImage image, Path path, WebPOptions options) throws IOException {
        Objects.requireNonNull(path,"path");
        byte[] encoded=encode(image,options);
        try(OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { out.write(encoded); }
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 255) << 24) | ((b[i+1] & 255) << 16) | ((b[i+2] & 255) << 8) | (b[i+3] & 255);
    }
}
