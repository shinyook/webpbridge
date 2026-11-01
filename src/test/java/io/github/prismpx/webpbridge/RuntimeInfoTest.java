package io.github.prismpx.webpbridge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeInfoTest {
    @Test void reportsActualVersionsAndPresentResources() {
        var info=WebP.runtimeInfo();
        assertTrue(info.available(),info.failureReason());
        assertEquals("1.6.0",info.encoderVersion()); assertEquals("1.6.0",info.decoderVersion());
        assertTrue(info.bundledPlatforms().contains(info.platform()));
        assertNull(info.failureReason()); assertSame(info,WebP.runtimeInfo());
        assertThrows(UnsupportedOperationException.class,()->info.bundledPlatforms().add("fake"));
    }
}
