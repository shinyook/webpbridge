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

## Final CI evidence

[Run 41300435343](https://github.com/shinyook/webp-jni/actions/runs/41300435343) validates code commit `e63637d9d38b44b05f5fa543d249f414e7f8626d`. All eight jobs passed:

| Hosted runtime | Java | Unit checks | Packaged-JAR checks |
| --- | --- | --- | --- |
| Linux amd64 | 17 / 25 | 32 each | 4 each |
| Windows Server 2025 amd64 | 17 / 25 | 32 each | 4 each |
| macOS aarch64 | 17 / 25 | 32 each | 4 each |

The additional Linux stress job passed three large-image tests, one 2,000-cycle real-JVM soak test, and three external-codec interoperability tests. The Linux native-memory job passed 2,000 codec cycles plus 1,000 malformed inputs under ASan/UBSan/LeakSanitizer (`detect_leaks=1`, no JVM suppressions). Test reports and platform JARs are attached to the run. These results close issues #1–#5 within their recorded acceptance criteria; they do not replace an hours/days endurance run, continuous fuzzing or a broad licensed real-world photographic corpus.
