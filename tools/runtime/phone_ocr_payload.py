"""Verify offline OCR payload, optionally add its JNI libraries to an unsigned APK."""
from pathlib import Path
import argparse, hashlib, struct, zipfile

def verify(root):
    pins = root / 'third_party/phone-ocr/SHA256SUMS'
    files = []
    for line in pins.read_text('utf-8').splitlines():
        digest, name = line.split(maxsplit=1)
        p = (root / name).resolve()
        if not p.is_relative_to(root.resolve()) or not p.is_file():
            raise ValueError('Missing or invalid OCR payload: ' + name)
        if hashlib.sha256(p.read_bytes()).hexdigest() != digest:
            raise ValueError('OCR digest mismatch: ' + name)
        if '/jniLibs/' in name:
            data = p.read_bytes()
            if data[:4] != b'\x7fELF': raise ValueError('Not ELF: ' + name)
            if data[4] == 2:
                offset = struct.unpack_from('<Q', data, 32)[0]
                size, count = struct.unpack_from('<HH', data, 54)
                for i in range(count):
                    start = offset + i * size
                    if struct.unpack_from('<I', data, start)[0] == 1 and struct.unpack_from('<Q', data, start + 48)[0] < 16384:
                        raise ValueError('64-bit OCR library not 16KiB aligned: ' + name)
            files.append((p, 'lib/' + p.parent.name + '/' + p.name))
    if len(files) != 16: raise ValueError('Expected four libraries for each of four Android ABIs')
    return files

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument('--apk')
    args = parser.parse_args()
    files = verify(Path(args.root))
    if args.apk:
        with zipfile.ZipFile(args.apk, 'a', compression=zipfile.ZIP_STORED) as archive:
            existing = set(archive.namelist())
            for file, name in files:
                if name in existing: raise ValueError('Duplicate JNI library: ' + name)
                archive.write(file, name)
            # A custom Android assets root must not silently drop the OCR models/licenses.
            for file in sorted((Path(args.root) / 'assets/phone-ocr').rglob('*')):
                if not file.is_file(): continue
                name = 'assets/phone-ocr/' + file.relative_to(Path(args.root) / 'assets/phone-ocr').as_posix()
                if name in existing:
                    if archive.read(name) != file.read_bytes(): raise ValueError('Conflicting OCR asset: ' + name)
                else: archive.write(file, name)
    print('Offline OCR payload PASS: 16 Android JNI libraries, wrapper, pinned Chinese/English models')

if __name__ == '__main__': main()
