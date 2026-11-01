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
- Apple run disables leak detection because it is unsupported by Apple ASan. Linux CI enables `detect_leaks=1` without JVM suppressions; its result must pass before issue #2 is closed.

The native adapter models only the JNI operations the bridge uses, rather than executing a JVM. The real JVM test is complementary. This is bounded regression validation, not an indefinite endurance test or a proof of absence of all possible leaks.
