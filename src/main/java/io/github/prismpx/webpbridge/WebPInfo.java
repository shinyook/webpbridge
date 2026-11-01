package io.github.prismpx.webpbridge;

import java.util.Objects;

/** Header metadata only; obtaining this record does not validate the full pixel bitstream.
 * @param width canvas width
 * @param height canvas height
 * @param hasAlpha header indicates an alpha channel
 * @param animated header indicates animation (decoding animation is unsupported)
 * @param format codec format when known from the header
 */
public record WebPInfo(int width, int height, boolean hasAlpha, boolean animated, Format format) {
    /** Compression information known from header parsing. */
    public enum Format {
        /** VP8 lossy pixels. */ LOSSY,
        /** VP8L lossless pixels. */ LOSSLESS,
        /** Not established from the available container header (for example animation). */ UNSPECIFIED
    }
    /** Validates record invariants. */
    public WebPInfo {
        if(width<=0 || height<=0) throw new IllegalArgumentException("Dimensions must be positive");
        Objects.requireNonNull(format,"format");
    }
    /** @return canvas pixel count without integer overflow */
    public long pixelCount() { return (long)width*height; }
}
