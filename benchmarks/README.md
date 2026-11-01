# WebP codec benchmarks

A standalone JMH 1.37 module. Benchmark-only dependencies do not enter the published library artifact.

## Compared implementations

- This project's JNI binding, libwebp 1.6.0.
- [usefulness/webp-imageio](https://github.com/usefulness/webp-imageio), Maven `com.github.usefulness:webp-imageio:0.11.0`: native ImageIO encoder/decoder with macOS ARM64 support.
- [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys), Maven `com.twelvemonkeys.imageio:imageio-webp:3.15.3`: Java decoder. WebP writing is unsupported, so there is no encoding result for this library.

Providers are instantiated by their exact SPI class names; ImageIO registry ordering cannot silently choose a different implementation.

## Reproduce

First build and install this project **for the machine running JMH**, following the root README's native build commands, then:

```sh
mvn install
mvn -f benchmarks/pom.xml package
java -cp benchmarks/target/benchmarks.jar io.github.shinyook.webp.benchmarks.CodecBenchmark
java -jar benchmarks/target/benchmarks.jar CodecBenchmark -foe true \
  -prof gc -rf json -rff benchmarks/results/local.json
```

The validation command prints encoded sizes and SHA-256 checksums. It fails on dimension mismatches, lossless pixel mismatches across all decoders/encoders, or different lossy decoded pixels between the two matched native encoders. Benchmark setup repeats these checks outside timed operations.

The default measurement is single-threaded average milliseconds per operation, two separate JVM forks, three 1-second warmup iterations and five 1-second measured iterations per fork. Each fork uses `-Xms256m -Xmx1g`. Results are returned to JMH to prevent dead-code elimination. All benchmark methods run serially. Run on an otherwise idle machine; record hardware, runtime and library versions with your results. The recorded run used JMH's default method order, with no randomized cross-library schedule, CPU affinity or thermal/power controls. No JNI checking, file I/O, image generation, initialization or correctness comparison is included in the timed operation.

For a **smoke check only**, not publishable performance evidence:

```sh
java -jar benchmarks/target/benchmarks.jar CodecBenchmark -foe true \
  -p dataset=ui256 -f 1 -wi 0 -i 1 -r 100ms
```

## Workloads and comparability

Both fixtures are generated from code, with no external assets or licensing dependencies:

| Dataset | Dimensions | Content |
| --- | --- | --- |
| `ui256` | 256×256 | Opaque flat-color grid, compressible UI-like pattern |
| `texture1024` | 1024×1024 | Opaque uniformly random RGB, Java Random seed 20261101; deliberately difficult to compress |

Both use `TYPE_INT_ARGB`. Modes: lossless quality/effort 75 or lossy quality 80; native compression method 4, exact RGB enabled, encoder threading disabled, sharp YUV disabled. These match the current binding's defaults. Decode benchmarks share the **same pre-encoded byte array** for each dataset/mode. Encode benchmarks start from the same BufferedImage.

Timing includes API-level copies/conversion and, for ImageIO, reader/writer creation, parameters, in-memory streams and disposal. It is an application-facing in-memory operation comparison, not a measurement of the C codec alone. Readers/writers are not pooled. Decode results retain each library's native BufferedImage layout rather than adding a post-decode normalization to a common raster layout. Native initialization and cold-start extraction are excluded.

The GC profiler's `gc.alloc.rate.norm` measures **Java heap bytes per operation**, not native allocations, process RSS, peak memory or leak freedom. Allocation rates in MB/s depend on throughput and should not be treated as per-image memory cost. JMH's score error is the 99.9% confidence-interval half-width; small apparent differences with overlapping intervals are inconclusive.

These synthetic opaque workloads do not represent photographs, alpha-heavy content, 4K/8K images, metadata, all BufferedImage types, concurrent throughput, cold starts, or other operating systems/CPUs/JDKs. Comparing different wrapper APIs also measures their different copying and ImageIO overheads. Do not generalize one machine's ranking into a universal performance claim.

Recorded results and environment are linked from the root README.

## Regenerate charts

Install the pinned plotting dependency from `benchmarks/requirements-charts.txt` in a virtual environment, then run `python benchmarks/charts.py`. The generator reads the same committed JMH JSON used by the tables and exports PNG and SVG files under `benchmarks/results/charts/`. Charts preserve all four workloads and include uncertainty; no timing measurements are rerun. The root README embeds PNGs for broad renderer support. Run `python benchmarks/report.py` separately to regenerate tables.
