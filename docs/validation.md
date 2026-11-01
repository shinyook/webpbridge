# Extended validation record

These checks are regression evidence for the specified workloads, not a proof of safety for arbitrary input, unlimited sizes, or indefinite runtimes.

## #1 Large images (2026-11-01)

Local environment: macOS ARM64, Azul JDK 25.0.3, libwebp 1.6.0, Java heap limit 2 GiB, JNI checks enabled. Command: `mvn -Pstress test`. Three tests passed:

| Workload | Mode | Round-trip and verification time |
| --- | --- | --- |
| 4096×4096 patterned RGBA | lossless | 4608 ms |
| 4096×4096 patterned RGBA | lossy, quality 80 | 2261 ms |
| 2048×2048 seeded random RGBA | lossless | 676 ms |
| 2048×2048 seeded random RGBA | lossy, quality 80 | 1207 ms |
| Two concurrent 2048×2048 patterned RGBA calls | lossless | 1431/1432 ms per call |

All lossless pixels and all lossy alpha values were compared. The 4096×4096 cases also verify rejection by the default 16-million-pixel decode limit and success with an explicit higher limit. Timing includes encode, decode and verification, excludes source generation, and is a single run rather than a benchmark. Linux stress CI has an external 15-minute job limit. No 8K, sustained concurrent load, peak RSS guarantee or production SLA is established by this suite.

## #2 Native allocation paths and JVM soak (2026-11-01)

Local macOS ARM64 / JDK 25.0.3 results:

- Actual JVM: 2,000 encode/decode cycles alternating lossy/lossless, lossless pixel comparisons, pixel-limit rejection and malformed-input rejection. `mvn -Pstress -Dtest=MemorySoakTest test`: passed in 16.01 s with `-Xcheck:jni`.
- Standalone native adapter: 2,000 codec cycles, truncation and pixel-limit rejection, simulated JNI input pin failure and Java output-array allocation failure. Actual JNI C entry points and bundled libwebp compiled with AddressSanitizer and UndefinedBehaviorSanitizer. Passed in 33.70 s, no diagnostics and no outstanding input-array pins.
- Apple run disables leak detection because it is unsupported by Apple ASan. Linux CI enables `detect_leaks=1` without JVM suppressions. Final run 41300435343 passed with no sanitizer diagnostics, including 1,000 additional malformed-input cases.

The native adapter models only the JNI operations the bridge uses, rather than executing a JVM. The real JVM test is complementary. This is bounded regression validation, not an indefinite endurance test or a proof of absence of all possible leaks.

## #3 Malformed inputs and boundaries (2026-11-01)

`mvn -Dtest=MalformedInputTest test` passed three tests on local macOS ARM64 / JDK 25.0.3. The deterministic corpus contains 11,238 decodes: all truncated prefixes of independently encoded lossy/lossless random images, seeded mutations, RIFF/chunk corruption, and random byte arrays. Successful mutated images are allowed if within the 4,096-pixel limit; other cases must produce `IllegalArgumentException`. Unexpected exceptions fail the test. Additional tests cover oversized VP8X headers, width 1/16383/16384, height 16384 rejection, quality 0/100, and invalid decode limits. Runtime was 0.456 s. The native sanitizer adapter also adds 1,000 truncated/mutated inputs. Fixed-seed mutation is regression coverage, not continuous coverage-guided fuzzing. The external CI job timeout bounds a stuck native call.

## #4 Image types and external codec interoperability (2026-11-01)

Local macOS ARM64 / JDK 25.0.3, cwebp/dwebp 1.6.0:

- 23 image-type tests passed: lossless pixel equality and lossy alpha equality for 11 BufferedImage types, plus a subimage with parent stride/offset. Types include packed RGB/ARGB, premultiplied alpha, BGR/ABGR, 8/16-bit grayscale, RGB565, binary and indexed images. Equality is against Java `getRGB` normalization, not original high-bit-depth samples or metadata.
- Three external-tool tests passed (`mvn -Pinterop test`): cwebp→Java and Java→dwebp for RGB, ARGB and grayscale-derived images, both lossy and lossless; and native grayscale-PNG input compared against dwebp. Paths containing spaces are included. External processes have a 30-second limit.
- A first comparison exposed differing grayscale PNG sample-to-RGB interpretation between Java ImageIO and cwebp. Source-pixel equality therefore uses an explicit 8-bit sRGB PNG normalization. Native grayscale PNG interoperability is checked against the independent dwebp decode of the same WebP, avoiding an incorrect cross-color-space equality assertion.

These are deterministic synthetic fixtures plus the original independent cwebp fixture. This is not a large real-world photographic corpus, nor ICC/EXIF preservation validation. The library intentionally does not preserve those metadata.

## #5 Packaged JAR loading (2026-11-01)

Four Maven Failsafe integration tests passed locally (macOS ARM64 / JDK 25.0.3): a fresh JVM with only the packaged JAR on its classpath, explicit native library loading from a path containing spaces, a JAR stripped of native resources producing the documented recovery message, and an invalid explicit path failing with `UnsatisfiedLinkError`. Successful child JVMs perform an exact lossless round trip and assert the codec classes came from a JAR. Bundled loading uses a temporary directory containing spaces. The suite runs at `mvn verify`, after packaging, on the OS/JDK CI matrix.

The platform matrix covers the architectures reported by each hosted runner; it does not establish all OS/architecture combinations, Linux musl compatibility, or support for older glibc versions. Multi-platform fat-JAR distribution is still outside this validation scope.

## #6 Options and explicit input limits (2026-11-01)

`WebPOptions` is an immutable, validated snapshot (quality 0–100, lossless flag, `maxPixels`, `maxInputBytes`); builders stay independent after `build()`. `OptionsTest` rejects NaN, infinite and out-of-range quality plus zero, negative and `Integer.MAX_VALUE` limits; checks the exact pixel and byte boundaries (limit passes, limit − 1 is rejected) for both encode and decode; and confirms a pixel-exact lossless round trip including alpha. The existing `encode(image, quality)`, `encodeLossless` and `decode(bytes[, maxPixels])` overloads keep their previous behaviour and limits, and the existing suites for them still pass unchanged.

The limits bound input size and decoded pixel count; they are not a total-memory budget.

## #7 Header inspection without pixel decoding (2026-11-01)

`WebP.inspect` reads the header through libwebp's `WebPGetFeatures` and allocates no decoded pixel buffer. `InspectTest` covers lossless with alpha, opaque lossy, a hand-built animated VP8X header (inspected, while decoding it is still rejected), empty, truncated and bad-magic inputs, and the byte limit. Inspection deliberately does not apply the pixel limit, so callers can decide about dimensions before decoding. A native inspect entry point was added to the sanitizer harness, and pins stay balanced under ASan/UBSan/LeakSanitizer.

A successful inspection only proves the header is readable. It does not prove the whole bitstream is valid.

## #8 Bounded file and stream I/O (2026-11-01)

Path and `InputStream` overloads for `read`/`inspect`, and Path/`OutputStream` overloads for `write`. `IoTest` checks round trips through a path containing spaces. Caller streams are never closed or flushed. Unknown-length and zero-progress streams are bounded while reading, so an endless stream under an 8-byte limit stops after 9 bytes. An existing file is never overwritten (`FileAlreadyExistsException`, contents preserved). A validation failure leaves the stream empty and creates no file, and stream `IOException`s propagate unchanged.

All data is buffered in memory, and there is no cancellation or timeout. A failure partway through I/O can leave a partial stream write or a partial newly created file.

## #9 Runtime diagnostics and service documentation (2026-11-01)

`WebP.runtimeInfo()` returns a cached, immutable snapshot with availability, failure reason, running platform, the platforms actually bundled in the JAR, and the encoder/decoder versions reported by the loaded libwebp (1.6.0). `RuntimeInfoTest` checks those values and confirms the platform list cannot be modified. A new packaged-JAR integration test starts a fresh JVM with a missing explicit native path, and repeated diagnostic calls return the same unavailable snapshot naming that path instead of throwing. The README now documents upload validation, options and limits, file/stream ownership, and startup diagnostics in English.

Diagnostics do not retry or repair a failed load (that needs a new class loader or JVM), and nothing here interrupts native calls.

### CI evidence for #6–#9

[Build and test run 41315518098](https://github.com/shinyook/webpbridge/actions/runs/41315518098) and [release-candidate run 41315557101](https://github.com/shinyook/webpbridge/actions/runs/41315557101) validate commit `7f7924a`. Every job passed; publishing was skipped by design.

| Hosted runtime | Java | Unit checks | Packaged-JAR checks |
| --- | --- | --- | --- |
| Linux amd64 | 17 / 25 | 43 each | 5 each |
| Windows amd64 | 17 / 25 | 43 each | 5 each |
| macOS aarch64 | 17 / 25 | 43 each | 5 each |

The stress job and the native-memory job (2,000 codec cycles, 1,000 malformed inputs, allocation/pin failure paths, no outstanding pins) also passed. The release-candidate run built native libraries for all three platforms, assembled the bundle, and verified it on each OS with JDK 17 and 25.

## #10 Opaque images decode as TYPE_INT_RGB (2026-11-01)

Decoding returns `TYPE_INT_RGB` when the bitstream has no alpha and `TYPE_INT_ARGB` otherwise, on every decode and read overload. `DecodedTypeTest` (4 tests) covers opaque lossy and lossless input (pixel-exact), transparent input, and a WebP → JPEG → read round trip through ImageIO. Before this change, `ImageIO.write(decoded, "jpg", …)` returned `false` for opaque input. The new tests fail on the previous implementation and pass on the new one.

Pixel values seen through `getRGB` are unchanged (opaque pixels report alpha 0xFF either way). Only the returned image type differs.

## #11 inspect accepts what decode accepts (2026-11-01)

`inspect` now uses the same container rules as decoding. The declared RIFF payload must be present, bytes after it are ignored, and raw VP8/VP8L bitstreams are accepted. Empty, truncated, bad-magic and non-WebP containers are still rejected. `InspectTest` adds a trailing-byte test and a consistency sweep of 10,246 inputs (every prefix of a lossy and a lossless stream up to 8 bytes past the end, plus 4,000 seeded mutations, seed 20261101). For all 3,537 inputs that `decode` accepted, `inspect` succeeded with the same width, height and alpha. Before the change, the trailing-byte and consistency tests failed.

`inspect` remains header-only. For raw bitstreams and for damage beyond the header, `inspect` can succeed where `decode` then fails.

## #12 Copy-free pixel paths (2026-11-01)

Decoding fills the returned image's `int[]` straight from libwebp's BGRA output (ARGB on big-endian hosts) and wraps it in a `DataBufferInt` raster. The intermediate `byte[]`, the per-pixel conversion and the `setRGB` copy are gone. Encoding passes packed ARGB ints row by row into `WebPPicture`. `TYPE_INT_ARGB` and `TYPE_INT_RGB` rasters, including subimages, are copied without color conversion; other types still use `getRGB`.

Equivalence was checked three ways:
- A local differential run compared the previous build with the new one on 198 cases: 11 image types, 3 sizes, full images and subimages, lossless, q80 and q5. Every encoded byte and every decoded pixel was identical.
- `PixelPathTest` asserts that the fast path and the generic path produce byte-identical lossless and lossy output, including subimages and a 1×1 corner subimage. It also checks that an undefined high byte in `TYPE_INT_RGB` rasters is ignored, and that decoded images are independent and writable.
- The native sanitizer harness covers the new `int[]` entry points, the opaque flag and the allocation and exception paths.

Local timing on a shared 2-vCPU Linux container (OpenJDK 21, 4K test image, same harness before and after, two rounds):

| Operation | Before | After |
| --- | --- | --- |
| Decode time | 460–468 ms | 363–391 ms |
| Java heap allocated per decode | 99.5 MB | 33.2 MB |
| Encode q80 time | 1,745–2,042 ms | 1,732–1,867 ms |
| Java heap allocated per encode | 71.5 MB | 38.4 MB |

Encode time is dominated by libwebp, so it is roughly unchanged. These are one machine's observations, not a benchmark. The README JMH charts predate this change and have not been regenerated.

## #13 Reclaiming extraction directories (2026-11-01)

Each process holds a lock on `.lock` in its extraction directory. Before extracting, the loader removes `webpbridge-*` directories under `java.io.tmpdir` in two cases: the lock can be acquired and the directory is more than 10 seconds old, or the directory has no lock file and is more than 1 hour old (left by earlier releases). Only directories containing nothing but the lock and the library are touched. Symbolic links are never followed, the library is deleted before the lock, and every failure is ignored.

`StaleCleanupTest` (7 tests) covers stale and legacy removal, both grace periods, a lock held in the same JVM, a lock held by another live JVM, foreign content, non-directories, unrelated names, symlinks (skipped where they cannot be created) and an unreadable root. A new packaged-JAR integration test plants a stale directory and runs two fresh JVMs that share one temporary directory. On Windows, the first JVM left its DLL directory behind on exit, exactly the problem being fixed, and the second JVM removed it. On Linux and macOS nothing was left after exit.

Cleanup only runs when a later process loads the bundled library. A host that never starts webpbridge again keeps its last directory. Directories in other users' temporary folders are not reachable.

### CI evidence for #10–#13

[Build and test run 41319840094](https://github.com/shinyook/webpbridge/actions/runs/41319840094) validates the code now at commit `3afb3d2`. All jobs passed. (These runs executed before an authorship-only history rewrite on 2026-11-01, when the identical trees were commits `ff996c8`, `32e33d7` and `dfbb03e`.)

| Hosted runtime | Java | Unit checks | Packaged-JAR checks |
| --- | --- | --- | --- |
| Linux amd64 | 17 / 25 | 59 each | 6 each |
| Windows amd64 | 17 / 25 | 59 each | 6 each |
| macOS aarch64 | 17 / 25 | 59 each | 6 each |

The stress job (large images, the 2,000-cycle JVM soak and cwebp/dwebp interoperability) and the native-memory job under ASan/UBSan/LeakSanitizer also passed. Intermediate commits `173dd0f` (#10, #11) and `3e966ac` (#12) also passed their own runs. [Release-candidate run 41320045896](https://github.com/shinyook/webpbridge/actions/runs/41320045896) on `3afb3d2` built all three native libraries, assembled the multi-platform JAR and verified it with fresh JVMs on all three operating systems with JDK 17 and 25 (publishing skipped).

## Final CI evidence for #1–#5

[Run 41300435343](https://github.com/shinyook/webpbridge/actions/runs/41300435343) validates code commit `e63637d9d38b44b05f5fa543d249f414e7f8626d`. All eight jobs passed:

| Hosted runtime | Java | Unit checks | Packaged-JAR checks |
| --- | --- | --- | --- |
| Linux amd64 | 17 / 25 | 32 each | 4 each |
| Windows Server 2025 amd64 | 17 / 25 | 32 each | 4 each |
| macOS aarch64 | 17 / 25 | 32 each | 4 each |

The additional Linux stress job passed three large-image tests, one 2,000-cycle real-JVM soak test, and three external-codec interoperability tests. The Linux native-memory job passed 2,000 codec cycles plus 1,000 malformed inputs under ASan/UBSan/LeakSanitizer (`detect_leaks=1`, no JVM suppressions). Test reports and platform JARs are attached to the run. These results close issues #1–#5 within their recorded acceptance criteria; they do not replace an hours/days endurance run, continuous fuzzing or a broad licensed real-world photographic corpus.
