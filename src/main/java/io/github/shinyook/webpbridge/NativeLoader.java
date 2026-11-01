package io.github.shinyook.webpbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;

final class NativeLoader {
    private NativeLoader() {}
    static final String PREFIX="webpbridge-";
    static final String LOCK=".lock";
    private static final Set<String> LIBRARIES=Set.of("libwebpjni.so","libwebpjni.dylib","webpjni.dll");
    /** Directories without a lock file are only removed once this old (e.g. from releases before locking). */
    static final Duration UNLOCKED_GRACE=Duration.ofHours(1);
    /** Closes the window between another loader creating its lock file and acquiring the lock. */
    static final Duration LOCKED_GRACE=Duration.ofSeconds(10);
    /** Held for the life of the class loader; the OS releases it when the process exits. */
    @SuppressWarnings("unused") private static FileLock ownLock;
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
            Path root = Path.of(System.getProperty("java.io.tmpdir"));
            cleanStale(root, Instant.now());
            Path dir = Files.createTempDirectory(PREFIX);
            dir.toFile().deleteOnExit();
            ownLock = lock(dir);
            Path library = dir.resolve(filename);
            library.toFile().deleteOnExit();
            Files.copy(in, library, StandardCopyOption.REPLACE_EXISTING);
            System.load(library.toAbsolutePath().toString());
        } catch (IOException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("Cannot extract WebP native library: " + e.getMessage());
            error.initCause(e); throw error;
        }
    }

    /** Marks dir as owned by this process. Best effort: without a lock the directory is simply never reclaimed by others until it ages. */
    private static FileLock lock(Path dir) {
        Path file=dir.resolve(LOCK);
        file.toFile().deleteOnExit();
        try {
            FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock();
            if(lock==null) channel.close();
            return lock;
        } catch(IOException | RuntimeException e) {
            return null;
        }
    }
    /**
     * Removes extraction directories left by processes that have exited (Windows cannot delete a loaded DLL at
     * shutdown). Never throws. A directory is removed only when it contains nothing but a lock file and a library
     * file, is older than a short grace period, and either its lock can be acquired (no live owner) or it has no lock
     * and is older than a longer grace period.
     * Symbolic links are never followed.
     * @return number of directories removed
     */
    static int cleanStale(Path root, Instant now) {
        int removed=0;
        List<Path> candidates=new ArrayList<>();
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root,PREFIX+"*")) {
            for(Path entry:entries) candidates.add(entry);
        } catch(IOException | RuntimeException e) {
            return 0;
        }
        for(Path dir:candidates) {
            try { if(removeIfStale(dir,now)) removed++; }
            catch(IOException | RuntimeException ignored) { /* best effort: another process, permissions or a race */ }
        }
        return removed;
    }
    private static boolean removeIfStale(Path dir, Instant now) throws IOException {
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)) return false;
        List<Path> files=new ArrayList<>();
        boolean hasLock=false;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)) {
            for(Path file:entries) {
                String name=file.getFileName().toString();
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return false;
                if(name.equals(LOCK)) hasLock=true;
                else if(!LIBRARIES.contains(name)) return false;
                files.add(file);
            }
        }
        FileTime modified=Files.getLastModifiedTime(dir,LinkOption.NOFOLLOW_LINKS);
        if(modified.toInstant().plus(hasLock ? LOCKED_GRACE : UNLOCKED_GRACE).isAfter(now)) return false;
        if(hasLock) {
            try(FileChannel channel=FileChannel.open(dir.resolve(LOCK),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock;
                try { lock=channel.tryLock(); }
                catch(OverlappingFileLockException heldByThisJvm) { return false; }
                if(lock==null) return false;
                lock.release();
            }
        }
        // Library first: if it is still mapped by some process (Windows), the lock file stays and nothing is lost.
        files.sort((a,b)->Boolean.compare(a.getFileName().toString().equals(LOCK),b.getFileName().toString().equals(LOCK)));
        for(Path file:files) Files.deleteIfExists(file);
        Files.delete(dir);
        return true;
    }
}
