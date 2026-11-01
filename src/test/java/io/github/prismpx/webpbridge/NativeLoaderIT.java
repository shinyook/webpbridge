package io.github.prismpx.webpbridge;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value=90, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class NativeLoaderIT {
    @TempDir Path temp;
    private final Path jar=Path.of(System.getProperty("webpbridge.test.jar", "target/webpbridge-0.1.0-SNAPSHOT.jar")).toAbsolutePath();
    private Path source() throws Exception {
        Path source=temp.resolve("Probe.java");
        Files.writeString(source,"""
            import io.github.prismpx.webpbridge.WebP;
            import java.awt.image.BufferedImage;
            class Probe {
                public static void main(String[] args) {
                    String origin=WebP.class.getProtectionDomain().getCodeSource().getLocation().toString();
                    if (!origin.endsWith(".jar")) throw new AssertionError("Not loaded from JAR: "+origin);
                    if(Boolean.getBoolean("probe.diagnostics")) {
                        var first=WebP.runtimeInfo(); var second=WebP.runtimeInfo();
                        if(first!=second || first.available() || first.failureReason()==null) throw new AssertionError("Bad repeated diagnostics");
                        System.out.println("PACKAGED_JAR_OK unavailable diagnostics: "+first.failureReason());
                        return;
                    }
                    var image=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0,0,0x00123456); image.setRGB(1,0,0x80112233);
                    var output=WebP.decode(WebP.encodeLossless(image));
                    for(int x=0;x<2;x++) if(image.getRGB(x,0)!=output.getRGB(x,0)) throw new AssertionError("pixel mismatch");
                    System.out.println("PACKAGED_JAR_OK "+origin);
                }
            }
            """);
        return source;
    }
    private String run(Path artifact, boolean success, String... options) throws Exception {
        Path scratch=Files.createDirectories(temp.resolve("temporary directory with spaces"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var args=new ArrayList<String>(List.of(java.toString(),"-Xcheck:jni","-Xmx128m","-Djava.io.tmpdir="+scratch));
        args.addAll(List.of(options)); args.addAll(List.of("-cp",artifact.toString(),source().toString()));
        Path log=temp.resolve("child.log");
        Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45,TimeUnit.SECONDS),"child JVM timed out");
            String output=Files.readString(log);
            if(success) { assertEquals(0,process.exitValue(),output); assertTrue(output.contains("PACKAGED_JAR_OK"),output); }
            else assertNotEquals(0,process.exitValue(),output);
            return output;
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    @Test void bundledNativeLoadsFromPackagedJarInFreshJvm() throws Exception {
        run(jar,true);
        System.out.println("JAR_LOADER os="+System.getProperty("os.name")+" arch="+System.getProperty("os.arch")+" java="+System.getProperty("java.version"));
    }
    @Test void explicitNativePathLoadsInFreshJvm() throws Exception {
        try(var archive=new JarFile(jar.toFile())) {
            String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String platform=os.startsWith("windows")?"windows":os.contains("mac")?"macos":"linux";
            String arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            arch=(arch.equals("arm64")||arch.equals("aarch64"))?"aarch64":"x86_64";
            String resource="native/"+platform+"-"+arch+"/"+System.mapLibraryName("webpjni");
            var entries=archive.stream().filter(e->e.getName().equals(resource)).toList();
            assertEquals(1,entries.size(),"Expected exactly one native library for the running platform");
            var entry=entries.get(0);
            Path nativeFile=Files.createDirectories(temp.resolve("explicit native with spaces")).resolve(Path.of(entry.getName()).getFileName());
            try(var in=archive.getInputStream(entry)) { Files.copy(in,nativeFile); }
            run(jar,true,"-Dwebp.jni.path="+nativeFile.toAbsolutePath());
        }
    }
    @Test void missingBundledBinaryExplainsRecovery() throws Exception {
        Path stripped=temp.resolve("without native.jar");
        try(var original=new JarFile(jar.toFile()); var out=new JarOutputStream(Files.newOutputStream(stripped))) {
            for(var entry:Collections.list(original.entries())) {
                if(entry.getName().startsWith("native/")) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                if(!entry.isDirectory()) try(var in=original.getInputStream(entry)) { in.transferTo(out); }
                out.closeEntry();
            }
        }
        String output=run(stripped,false);
        assertTrue(output.contains("UnsatisfiedLinkError"),output);
        assertTrue(output.contains("Missing /native/"),output);
        assertTrue(output.contains("webp.jni.path"),output);
    }
    @Test void unavailableDiagnosticsAreStableInFreshJvm() throws Exception {
        String output=run(jar,true,"-Dwebp.jni.path="+temp.resolve("missing-native"),"-Dprobe.diagnostics=true");
        assertTrue(output.contains("unavailable diagnostics"),output);
        assertTrue(output.contains("missing-native"),output);
    }
    @Test void invalidExplicitPathFailsInFreshJvm() throws Exception {
        String output=run(jar,false,"-Dwebp.jni.path="+temp.resolve("does-not-exist"));
        assertTrue(output.contains("UnsatisfiedLinkError"),output);
        assertTrue(output.contains("does-not-exist"),output);
    }
    private static List<Path> extractionDirs(Path scratch) throws Exception {
        try(var entries=Files.newDirectoryStream(scratch,"webpbridge-*")) { var list=new ArrayList<Path>(); entries.forEach(list::add); return list; }
    }
    @Test void laterProcessRemovesStaleExtractionDirectories() throws Exception {
        Path scratch=Files.createDirectories(temp.resolve("temporary directory with spaces"));
        var old=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        Path planted=Files.createDirectory(scratch.resolve("webpbridge-planted"));
        Files.write(planted.resolve(System.mapLibraryName("webpjni")),new byte[]{1,2,3});
        Files.createFile(planted.resolve(".lock"));
        Files.setLastModifiedTime(planted,old);
        run(jar,true);
        assertFalse(Files.exists(planted),"stale directory from an exited process was not removed");
        // Windows cannot delete the loaded DLL at exit, so the first run's directory usually remains here.
        var leftovers=extractionDirs(scratch);
        for(Path dir:leftovers) Files.setLastModifiedTime(dir,old);
        run(jar,true);
        for(Path dir:leftovers) assertFalse(Files.exists(dir),"leftover from previous JVM not removed: "+dir);
        System.out.println("STALE_CLEANUP os="+System.getProperty("os.name")+" leftovers-after-exit="+leftovers.size()+" after-second-run="+extractionDirs(scratch).size());
    }
}
