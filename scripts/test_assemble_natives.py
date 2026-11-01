import tempfile
import unittest
from pathlib import Path
from assemble_natives import SUPPORTED, assemble


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'inputs'
        self.source.mkdir()
        self.destination = self.root / 'output'
        for platform, name in SUPPORTED.items():
            artifact = self.source / platform
            native = artifact / 'native' / platform / name
            native.parent.mkdir(parents=True)
            native.write_bytes(platform.encode())
            for notice in ['COPYING', 'PATENTS', 'AUTHORS']:
                p = artifact / 'META-INF/licenses/libwebp' / notice
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(notice)

    def test_complete_bundle_copies_each_native_and_notices(self):
        assemble(self.source, self.destination)
        for platform, name in SUPPORTED.items():
            self.assertEqual((self.destination / 'native' / platform / name).read_bytes(), platform.encode())
        self.assertEqual(len(list((self.destination / 'META-INF/licenses/libwebp').iterdir())), 3)

    def test_missing_platform_is_rejected_before_copying(self):
        (self.source / 'linux-x86_64/native/linux-x86_64/libwebpjni.so').unlink()
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            assemble(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_duplicate_platform_is_rejected(self):
        p = self.source / 'duplicate/native/linux-x86_64/libwebpjni.so'
        p.parent.mkdir(parents=True)
        p.write_bytes(b'duplicate')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            assemble(self.source, self.destination)

    def test_windows_license_line_endings_are_normalized(self):
        for platform in SUPPORTED:
            for notice in ['COPYING', 'PATENTS', 'AUTHORS']:
                p = self.source / platform / 'META-INF/licenses/libwebp' / notice
                p.write_bytes((notice+'\r\n' if platform == 'windows-x86_64' else notice+'\n').encode())
        assemble(self.source, self.destination)
        self.assertEqual((self.destination/'META-INF/licenses/libwebp/PATENTS').read_bytes(), b'PATENTS\n')

    def test_conflicting_licenses_are_rejected(self):
        (self.source / 'linux-x86_64/META-INF/licenses/libwebp/COPYING').write_text('different version')
        with self.assertRaisesRegex(ValueError, 'License mismatch'):
            assemble(self.source, self.destination)

    def test_unexpected_payload_is_rejected(self):
        (self.source / 'linux-x86_64/unexpected.txt').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'Unexpected payload'):
            assemble(self.source, self.destination)

    def test_existing_native_destination_is_rejected(self):
        (self.destination / 'native').mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'already contains'):
            assemble(self.source, self.destination)


if __name__ == '__main__':
    unittest.main()
