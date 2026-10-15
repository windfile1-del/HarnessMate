#!/usr/bin/env python3
"""Produce an offline extension ZIP without placing its payload in APK assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import uuid
import zipfile

FAMILIES = {'linux-jvm', 'linux-android', 'linux-lsp'}

def build_pack(family: str, manifest_path: Path, payload_path: Path, output_path: Path, publisher_envelope: Path | None = None):
    if family not in FAMILIES:
        raise ValueError('Unknown extension family')
    manifest_bytes = manifest_path.read_bytes()
    if len(manifest_bytes) > 32768:
        raise ValueError('Extension metadata too large')
    manifest = json.loads(manifest_bytes)
    if manifest.get('schema') != 1 or manifest.get('payload') != 'tools.tar.gz':
        raise ValueError('Invalid extension manifest')
    size = payload_path.stat().st_size
    if size != manifest.get('compressedBytes') or not 0 < size <= 1024 * 1024 * 1024:
        raise ValueError('Extension size mismatch')
    sha = hashlib.sha256()
    with payload_path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            sha.update(chunk)
    if sha.hexdigest() != manifest.get('sha256'):
        raise ValueError('Extension checksum mismatch')
    output_path.parent.mkdir(parents=True, exist_ok=True)
    stage = output_path.with_name(output_path.name + '.partial-' + uuid.uuid4().hex)
    try:
        with zipfile.ZipFile(stage, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            metadata = zipfile.ZipInfo('manifest.json')
            metadata.compress_type = zipfile.ZIP_STORED
            archive.writestr(metadata, manifest_bytes)
            payload = zipfile.ZipInfo('tools.tar.gz')
            payload.compress_type = zipfile.ZIP_STORED
            payload.file_size = size
            with payload_path.open('rb') as source, archive.open(payload, 'w', force_zip64=False) as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
            if publisher_envelope is not None:
                envelope = publisher_envelope.read_bytes()
                if not 0 < len(envelope) <= 32768:
                    raise ValueError('Publisher metadata size invalid')
                archive.writestr('publisher.json', envelope, compress_type=zipfile.ZIP_STORED)
        os.replace(stage, output_path)
    finally:
        stage.unlink(missing_ok=True)
    return output_path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--family', required=True, choices=sorted(FAMILIES))
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--payload', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--publisher', type=Path, help='Detached publisher.json produced by publisher_signing.cjs; unsigned drafts require sign_tool_packs.py before publication')
    args = parser.parse_args()
    print(build_pack(args.family, args.manifest, args.payload, args.output, args.publisher))

if __name__ == '__main__':
    main()
