package io.github.shinyook.webpbridge;

import java.awt.image.BufferedImage;
import java.util.Objects;

/** In-memory static WebP encoding and decoding. Methods are safe for concurrent use. */
public final class WebP {
    private WebP() {}
    private static final class Native {
        static { NativeLoader.load(); }
        static native byte[] encode(byte[] rgba, int width, int height, float quality, boolean lossless);
        static native byte[] decode(byte[] data, int maxPixels);
        static native byte[] inspect(byte[] data);
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
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgba = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], j = i * 4;
            rgba[j] = (byte) (c >>> 16); rgba[j + 1] = (byte) (c >>> 8);
            rgba[j + 2] = (byte) c; rgba[j + 3] = (byte) (c >>> 24);
        }
        return Native.encode(rgba, w, h, quality, lossless);
    }

    /** Decodes a static image, with a default limit of 16 million pixels. */
    public static BufferedImage decode(byte[] data) { return decode(data, 16_000_000); }

    /** Rejects images above maxPixels before allocating the decoded pixel buffer. */
    public static BufferedImage decode(byte[] data, int maxPixels) {
        Objects.requireNonNull(data, "data");
        if (maxPixels <= 0 || maxPixels > (Integer.MAX_VALUE - 8) / 4)
            throw new IllegalArgumentException("invalid maxPixels");
        byte[] raw = Native.decode(data, maxPixels);
        int w = readInt(raw, 0), h = readInt(raw, 4);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int j = 8 + 4 * i;
            pixels[i] = ((raw[j + 3] & 255) << 24) | ((raw[j] & 255) << 16)
                | ((raw[j + 1] & 255) << 8) | (raw[j + 2] & 255);
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        return image;
    }
    private static int readInt(byte[] b, int i) {
        return ((b[i] & 255) << 24) | ((b[i+1] & 255) << 16) | ((b[i+2] & 255) << 8) | (b[i+3] & 255);
    }
}
