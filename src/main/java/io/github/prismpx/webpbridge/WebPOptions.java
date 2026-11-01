package io.github.prismpx.webpbridge;

/** Immutable per-call configuration. Limits are independent, not a total memory budget. */
public final class WebPOptions {
    /** Shared immutable default configuration: quality 80, lossy, 16M pixels, 64 MiB input. */
    public static final WebPOptions DEFAULT = builder().build();
    private final float quality;
    private final boolean lossless;
    private final int maxPixels;
    private final int maxInputBytes;
    private WebPOptions(Builder b) {
        if (!Float.isFinite(b.quality) || b.quality < 0 || b.quality > 100)
            throw new IllegalArgumentException("quality must be finite and between 0 and 100");
        if (b.maxPixels <= 0 || b.maxPixels > (Integer.MAX_VALUE - 8) / 4)
            throw new IllegalArgumentException("invalid maxPixels");
        if (b.maxInputBytes <= 0 || b.maxInputBytes > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("invalid maxInputBytes");
        quality=b.quality; lossless=b.lossless; maxPixels=b.maxPixels; maxInputBytes=b.maxInputBytes;
    }
    /** @return a fresh builder with default settings */
    public static Builder builder() { return new Builder(); }
    /** @return lossy quality or lossless compression effort, in [0,100] */
    public float quality() { return quality; }
    /** @return whether exact lossless encoding is requested */
    public boolean lossless() { return lossless; }
    /** @return maximum pixels for option-based encoding/decoding */
    public int maxPixels() { return maxPixels; }
    /** @return maximum encoded input bytes for option-based decode, inspect and read */
    public int maxInputBytes() { return maxInputBytes; }
    /** A reusable mutable builder; built configurations are independent snapshots. */
    public static final class Builder {
        private float quality=80;
        private boolean lossless;
        private int maxPixels=16_000_000;
        private int maxInputBytes=64*1024*1024;
        private Builder() {}
        /** @param value lossy quality or lossless effort @return this builder */
        public Builder quality(float value) { quality=value; return this; }
        /** @param value exact lossless mode @return this builder */
        public Builder lossless(boolean value) { lossless=value; return this; }
        /** @param value maximum decoded/source pixels @return this builder */
        public Builder maxPixels(int value) { maxPixels=value; return this; }
        /** @param value maximum encoded input bytes @return this builder */
        public Builder maxInputBytes(int value) { maxInputBytes=value; return this; }
        /** @return a validated immutable snapshot @throws IllegalArgumentException for invalid settings */
        public WebPOptions build() { return new WebPOptions(this); }
    }
}
