# webp-jni

A small Java 17+ library for in-memory static WebP encoding and decoding using JNI and Google's libwebp. No external conversion process is spawned.

```java
import io.github.shinyook.webp.WebP;

byte[] compressed = WebP.encode(image, 80f);
byte[] lossless = WebP.encodeLossless(image);
BufferedImage restored = WebP.decode(compressed);
// Optional limit checked before allocating native decoded pixels:
BufferedImage limited = WebP.decode(compressed, 4_000_000);
```

## Build

Requires JDK 17+, Maven, CMake 3.20+, Git and a C compiler. Set `JAVA_HOME` to your JDK. CMake downloads pinned libwebp v1.6.0 and statically links it into the JNI library; no system libwebp installation is required.

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel
cmake --install build --config Release --prefix target/classes --component webpjni
mvn verify
```

The resulting `target/webp-jni-0.1.0-SNAPSHOT.jar` contains the native library for **the build machine's OS/CPU only** and libwebp license notices. Run `mvn install` to use that local artifact from another Maven project:

```xml
<dependency>
  <groupId>io.github.shinyook</groupId>
  <artifactId>webp-jni</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

This artifact is not yet published to Maven Central. CI builds and tests Linux, Windows and macOS with Java 17 and 25, uploading separate platform JARs. Cross-platform fat-JAR distribution is not implemented yet. `mvn clean` removes the installed native resource: repeat the CMake install step afterward.

## Example

After building, convert a PNG or JPEG to lossless WebP:

```sh
java -cp target/webp-jni-0.1.0-SNAPSHOT.jar examples/Convert.java input.png output.webp
```

## Native loading

The loader selects `/native/<os>-<arch>/<library>` from the JAR, extracts it into a private temporary directory, then loads it. Supported platform names are linux, macos and windows; architectures x86_64 and aarch64. Support requires building and testing for the actual target; Linux binaries depend on the build system's libc. Alternatively set `-Dwebp.jni.path=/absolute/path/to/library` to load an explicitly built JNI library. An executable temporary filesystem is required for bundled loading. On recent JDKs, use `--enable-native-access=ALL-UNNAMED` when required by the runtime.

## Behavior and limits

- Lossy quality is a finite number from 0 to 100. Alpha is preserved.
- Lossless encoding uses `exact=1`, preserving RGB even under fully transparent pixels.
- Images are normalized through `BufferedImage.getRGB` to 8-bit sRGB RGBA. Source metadata, ICC profiles and high bit depth are not preserved.
- Animation is rejected. This is not an ImageIO plugin.
- Default decoding limit: 16 million pixels. Override via `decode(bytes, maxPixels)`.
- Invalid input/options produce `IllegalArgumentException`; native encoding failures produce `IllegalStateException`; missing binaries produce `UnsatisfiedLinkError`.
- Calls use independent native state and can run concurrently. Each call copies pixel data; account for Java and native memory in addition to the pixel limit. No streaming, cancellation or decode timeout is provided.

## License

Java/JNI wrapper: MIT. Bundled libwebp: upstream BSD-style license and patent grant, copied into `META-INF/licenses/libwebp` during the native install step. See [libwebp](https://github.com/webmproject/libwebp).
