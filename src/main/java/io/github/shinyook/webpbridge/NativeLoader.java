package io.github.shinyook.webpbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;

final class NativeLoader {
    private NativeLoader() {}
    static String platform() {
        String osName=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os=osName.startsWith("windows")?"windows":osName.contains("mac")?"macos":osName.contains("linux")?"linux":"unsupported";
        String archName=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String arch=switch(archName) { case "amd64","x86_64" -> "x86_64"; case "aarch64","arm64" -> "aarch64"; default -> archName; };
        return os+"-"+arch;
    }
    static List<String> bundledPlatforms() {
        return List.of("linux-x86_64","linux-aarch64","windows-x86_64","windows-aarch64","macos-x86_64","macos-aarch64").stream()
            .filter(platform -> {
                String name=platform.startsWith("windows")?"webpjni.dll":platform.startsWith("macos")?"libwebpjni.dylib":"libwebpjni.so";
                return NativeLoader.class.getResource("/native/"+platform+"/"+name)!=null;
            }).toList();
    }
    static void load() {
        String explicit = System.getProperty("webp.jni.path");
        if (explicit != null) { System.load(Path.of(explicit).toAbsolutePath().toString()); return; }
        String filename = System.mapLibraryName("webpjni");
        String resource = "/native/" + platform() + "/" + filename;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) throw new UnsatisfiedLinkError("Missing " + resource + "; build for this platform or set -Dwebp.jni.path=/absolute/path/to/" + filename);
            Path dir = Files.createTempDirectory("webpbridge-");
            dir.toFile().deleteOnExit();
            Path library = dir.resolve(filename);
            library.toFile().deleteOnExit();
            Files.copy(in, library, StandardCopyOption.REPLACE_EXISTING);
            System.load(library.toAbsolutePath().toString());
        } catch (IOException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("Cannot extract WebP native library: " + e.getMessage());
            error.initCause(e); throw error;
        }
    }
}
