"""Negative ELF delivery checks against the built source payload; never creates an APK."""
import importlib.util
from pathlib import Path
import struct
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('adb_payload', ROOT / 'tools/runtime/adb_payload.py')
payload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class AdbPayloadTests(unittest.TestCase):
    def test_abi_and_headers(self):
        for abi, machine in payload.MACHINES.items():
            data = (ROOT / 'third_party/adb/jniLibs' / abi / 'libvegaadb.so').read_bytes()
            self.assertEqual(set(payload.inspect_elf(data, machine)), {'libc.so', 'libm.so', 'libdl.so', 'libandroid.so'})
            for malformed in (data[:63], b'BAD!' + data[4:]):
                with self.assertRaises(ValueError):
                    payload.inspect_elf(malformed, machine)
            with self.assertRaises(ValueError):
                payload.inspect_elf(data, 62 if machine == 183 else 183)

    def test_reject_page_alignment_and_external_crypto(self):
        for abi, machine in payload.MACHINES.items():
            data = bytearray((ROOT / 'third_party/adb/jniLibs' / abi / 'libvegaadb.so').read_bytes())
            phoff = struct.unpack_from('<Q', data, 32)[0]
            entsize, count = struct.unpack_from('<HH', data, 54)
            low_alignment = bytearray(data)
            for index in range(count):
                offset = phoff + index * entsize
                if struct.unpack_from('<I', data, offset)[0] == 1:
                    struct.pack_into('<Q', low_alignment, offset + 48, 4096)
                    break
            with self.assertRaises(ValueError):
                payload.inspect_elf(low_alignment, machine)
            with self.assertRaisesRegex(ValueError, 'external native dependency'):
                payload.inspect_elf(data.replace(b'libm.so\x00', b'libx.so\x00'), machine)


if __name__ == '__main__':
    unittest.main()
