package io.github.prismpx.webpbridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StaleCleanupTest {
    @TempDir Path root;
    private final Instant now=Instant.now();
    private static final String LIB=System.mapLibraryName("webpjni");

    private Path extraction(String name, boolean lock, Duration age) throws Exception {
        Path dir=Files.createDirectory(root.resolve(NativeLoader.PREFIX+name));
        Files.write(dir.resolve(LIB),new byte[]{1,2,3});
        if(lock) Files.createFile(dir.resolve(NativeLoader.LOCK));
        Files.setLastModifiedTime(dir,FileTime.from(now.minus(age)));
        return dir;
    }
    @Test void removesStaleLockedAndOldUnlockedDirectories() throws Exception {
        Path stale=extraction("stale",true,Duration.ofMinutes(5));
        Path legacy=extraction("legacy",false,Duration.ofHours(2));
        assertEquals(2,NativeLoader.cleanStale(root,now));
        assertFalse(Files.exists(stale)); assertFalse(Files.exists(legacy));
    }
    @Test void keepsRecentDirectoriesInsideGracePeriods() throws Exception {
        Path creating=extraction("creating",true,Duration.ofSeconds(1));
        Path recentLegacy=extraction("recent",false,Duration.ofMinutes(30));
        assertEquals(0,NativeLoader.cleanStale(root,now));
        assertTrue(Files.exists(creating.resolve(LIB))); assertTrue(Files.exists(recentLegacy.resolve(LIB)));
    }
    @Test void keepsDirectoryLockedInThisJvm() throws Exception {
        Path live=extraction("live",true,Duration.ofMinutes(5));
        try(var channel=FileChannel.open(live.resolve(NativeLoader.LOCK),StandardOpenOption.WRITE); var lock=channel.lock()) {
            assertEquals(0,NativeLoader.cleanStale(root,now));
            assertTrue(Files.exists(live.resolve(LIB)));
        }
        assertEquals(1,NativeLoader.cleanStale(root,now));
    }
    @Test void keepsDirectoryLockedByAnotherJvm() throws Exception {
        Path live=extraction("other",true,Duration.ofMinutes(5));
        Path holder=root.resolve("Holder.java");
        Files.writeString(holder,"""
            import java.nio.channels.FileChannel; import java.nio.file.*;
            class Holder { public static void main(String[] a) throws Exception {
                try(var c=FileChannel.open(Path.of(a[0]),StandardOpenOption.WRITE); var l=c.lock()) {
                    System.out.println("LOCKED"); System.out.flush(); System.in.read();
                } } }
            """);
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Process process=new ProcessBuilder(java.toString(),holder.toString(),live.resolve(NativeLoader.LOCK).toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var reader=new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            do { line=reader.readLine(); } while(line!=null && !line.equals("LOCKED"));
            assertEquals("LOCKED",line,"holder JVM did not acquire the lock");
            assertEquals(0,NativeLoader.cleanStale(root,now));
            assertTrue(Files.exists(live.resolve(LIB)));
        } finally {
            process.getOutputStream().write('\n'); process.getOutputStream().flush();
            assertTrue(process.waitFor(30,TimeUnit.SECONDS)); 
        }
        assertEquals(1,NativeLoader.cleanStale(root,now));
        assertFalse(Files.exists(live));
    }
    @Test void ignoresForeignContentAndUnrelatedNames() throws Exception {
        Path foreign=extraction("foreign",true,Duration.ofMinutes(5));
        Files.writeString(foreign.resolve("notes.txt"),"keep");
        Path nested=extraction("nested",true,Duration.ofMinutes(5));
        Files.createDirectory(nested.resolve("sub"));
        Path plainFile=Files.writeString(root.resolve(NativeLoader.PREFIX+"file"),"keep");
        Path unrelated=Files.createDirectory(root.resolve("other-tool-123"));
        assertEquals(0,NativeLoader.cleanStale(root,now));
        assertTrue(Files.exists(foreign.resolve("notes.txt"))); assertTrue(Files.exists(nested.resolve("sub")));
        assertTrue(Files.exists(plainFile)); assertTrue(Files.exists(unrelated));
    }
    @Test void neverFollowsSymbolicLinks() throws Exception {
        Path victim=Files.createDirectory(root.resolve("victim"));
        Files.write(victim.resolve(LIB),new byte[]{1}); Files.createFile(victim.resolve(NativeLoader.LOCK));
        Files.setLastModifiedTime(victim,FileTime.from(now.minus(Duration.ofHours(2))));
        Path link=root.resolve(NativeLoader.PREFIX+"link");
        try { Files.createSymbolicLink(link,victim); }
        catch(UnsupportedOperationException | java.io.IOException noSymlinks) { return; } // e.g. Windows without privilege
        assertEquals(0,NativeLoader.cleanStale(root,now));
        assertTrue(Files.exists(victim.resolve(LIB))); assertTrue(Files.isSymbolicLink(link));
    }
    @Test void failuresAreIgnored() {
        assertEquals(0,NativeLoader.cleanStale(root.resolve("does-not-exist"),now));
    }
}
