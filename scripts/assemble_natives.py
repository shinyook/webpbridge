#!/usr/bin/env python3
"""Assemble only the explicitly supported, independently tested native artifacts."""
import argparse
import hashlib
import shutil
from pathlib import Path

SUPPORTED = {
    'linux-x86_64': 'libwebpjni.so',
    'windows-x86_64': 'webpjni.dll',
    'macos-aarch64': 'libwebpjni.dylib',
}

def assemble(source, destination):
    found = {}
    licenses = {}
    for artifact in sorted(source.iterdir()):
        if not artifact.is_dir():
            raise ValueError(f'Unexpected artifact: {artifact.name}')
        for path in artifact.rglob('*'):
            if not path.is_file():
                continue
            relative = path.relative_to(artifact)
            parts = relative.parts
            if len(parts) == 3 and parts[0] == 'native':
                platform, name = parts[1:]
                if SUPPORTED.get(platform) != name or platform in found:
                    raise ValueError(f'Unexpected or duplicate native library: {relative}')
                found[platform] = path
            elif str(relative).replace('\\', '/').startswith('META-INF/licenses/libwebp/'):
                name = parts[-1]
                if name not in {'COPYING', 'PATENTS', 'AUTHORS'}:
                    raise ValueError(f'Unexpected license file: {relative}')
                content = path.read_bytes()
                if name in licenses and licenses[name] != content:
                    raise ValueError(f'License mismatch across platforms: {name}')
                licenses[name] = content
            else:
                raise ValueError(f'Unexpected payload: {relative}')
    if set(found) != set(SUPPORTED) or set(licenses) != {'COPYING', 'PATENTS', 'AUTHORS'}:
        raise ValueError('Incomplete native platform set or libwebp license notices')
    if (destination / 'native').exists():
        raise ValueError('Destination already contains native libraries; use a fresh directory')
    for platform, path in found.items():
        target = destination / 'native' / platform / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        print(f'{platform}: sha256={hashlib.sha256(path.read_bytes()).hexdigest()}')
    for name, content in licenses.items():
        target = destination / 'META-INF/licenses/libwebp' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    assemble(args.source, args.destination)
