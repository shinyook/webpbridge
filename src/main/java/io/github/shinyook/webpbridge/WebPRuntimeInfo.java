package io.github.shinyook.webpbridge;

import java.util.List;
import java.util.Objects;

/** Immutable startup diagnostics. Querying attempts native initialization once per class loader.
 * @param platform normalized running platform
 * @param bundledPlatforms platforms whose native resources are actually present
 * @param available whether native initialization and version calls succeeded
 * @param encoderVersion actual native encoder version, or null when unavailable
 * @param decoderVersion actual native decoder version, or null when unavailable
 * @param failureReason load failure description, or null when available
 */
public record WebPRuntimeInfo(String platform, List<String> bundledPlatforms, boolean available,
        String encoderVersion, String decoderVersion, String failureReason) {
    /** Defensively copies the platform list. */
    public WebPRuntimeInfo {
        Objects.requireNonNull(platform,"platform");
        bundledPlatforms=List.copyOf(bundledPlatforms);
        if(available) {
            Objects.requireNonNull(encoderVersion,"encoderVersion"); Objects.requireNonNull(decoderVersion,"decoderVersion");
            if(failureReason!=null) throw new IllegalArgumentException("Available runtime cannot have a failure reason");
        } else {
            Objects.requireNonNull(failureReason,"failureReason");
            if(encoderVersion!=null || decoderVersion!=null) throw new IllegalArgumentException("Unavailable runtime has no versions");
        }
    }
}
