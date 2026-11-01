#!/usr/bin/env python3
"""Render the committed JMH results into README tables (Python standard library)."""
import csv
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parent
results = json.loads((root / 'results/macos-arm64-jdk25.json').read_text())
expected = {(method, dataset, mode)
            for method in ['encodeJni', 'encodeImageio', 'decodeJni', 'decodeImageio', 'decodeTwelveMonkeys']
            for dataset in ['ui256', 'texture1024'] for mode in ['lossless', 'lossy80']}
indexed = {(r['benchmark'].rsplit('.', 1)[-1], r['params']['dataset'], r['params']['compression']): r for r in results}
assert set(indexed) == expected, 'Missing, duplicate, or unexpected benchmark cases'
assert len(results) == len(expected), 'Duplicate benchmark entries'
for r in results:
    assert r['primaryMetric']['scoreUnit'] == 'ms/op'
    assert r['forks'] == 2 and r['measurementIterations'] == 5 and r['warmupIterations'] == 3
    assert math.isfinite(r['primaryMetric']['score']) and math.isfinite(r['primaryMetric']['scoreError'])

def cell(method, dataset, mode):
    m = indexed[method, dataset, mode]['primaryMetric']
    return f"{m['score']:.3f} ± {m['scoreError']:.3f}"

lines = ['<!-- BEGIN BENCHMARK TABLES -->',
         'Average **ms/op ± JMH 99.9% confidence-interval half-width**; lower is better. Each cell contains 10 measured iterations across two forks.',
         '', '### Encoding', '',
         '| Dataset | Mode | webp-jni | webp-imageio 0.11.0 |',
         '| --- | --- | ---: | ---: |']
for dataset in ['ui256', 'texture1024']:
    for mode in ['lossless', 'lossy80']:
        lines.append(f'| {dataset} | {mode} | {cell("encodeJni", dataset, mode)} | {cell("encodeImageio", dataset, mode)} |')
lines += ['', '### Decoding', '',
          '| Dataset | Mode | webp-jni | webp-imageio 0.11.0 | TwelveMonkeys 3.15.3 |',
          '| --- | --- | ---: | ---: | ---: |']
for dataset in ['ui256', 'texture1024']:
    for mode in ['lossless', 'lossy80']:
        lines.append(f'| {dataset} | {mode} | {cell("decodeJni", dataset, mode)} | {cell("decodeImageio", dataset, mode)} | {cell("decodeTwelveMonkeys", dataset, mode)} |')
lines += ['', '### Java heap allocation', '',
          'Measured **KiB/op** (`gc.alloc.rate.norm` / 1024). These are Java allocations, **not native memory or peak RSS**. See raw JSON for allocation confidence intervals.', '',
          '| Dataset | Mode | Encode JNI | Encode ImageIO | Decode JNI | Decode ImageIO | Decode TwelveMonkeys |',
          '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
for dataset in ['ui256', 'texture1024']:
    for mode in ['lossless', 'lossy80']:
        values = [indexed[m, dataset, mode]['secondaryMetrics']['gc.alloc.rate.norm']['score'] / 1024
                  for m in ['encodeJni', 'encodeImageio', 'decodeJni', 'decodeImageio', 'decodeTwelveMonkeys']]
        lines.append(f'| {dataset} | {mode} | ' + ' | '.join(f'{v:.1f}' for v in values) + ' |')
lines += ['', '### Encoded output sizes', '',
          'Both native encoders produced byte-identical output for every fixture in this run; [SHA-256 checksums](benchmarks/results/output-sizes.csv) are retained.', '',
          '| Dataset | Mode | webp-jni bytes | webp-imageio bytes |',
          '| --- | --- | ---: | ---: |']
with (root / 'results/output-sizes.csv').open() as stream:
    for row in csv.DictReader(stream):
        assert row['jniSha256'] == row['imageioSha256'], 'Outputs differ; revise the comparability statement'
        lines.append(f"| {row['dataset']} | {row['compression']} | {int(row['jniBytes']):,} | {int(row['imageioBytes']):,} |")
lines += ['<!-- END BENCHMARK TABLES -->']
fragment = '\n'.join(lines)
readme = root.parent / 'README.md'
text = readme.read_text()
start, end = '<!-- BEGIN BENCHMARK TABLES -->', '<!-- END BENCHMARK TABLES -->'
assert start in text and end in text, 'README table markers are missing'
a, b = text.index(start), text.index(end) + len(end)
readme.write_text(text[:a] + fragment + text[b:])
print('Updated README benchmark tables from all 20 recorded cases.')
