#!/usr/bin/env python3
"""Reject known removed key material in files, archives and decoded Git objects.

Only SHA-256 digests of four 32-byte modulus segments are retained, not key bytes.
This is a targeted regression check, not proof against every possible encoding.
"""
from pathlib import Path
import argparse
import base64
import gzip
import hashlib
import io
import json
import lzma
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', '.gradle', '.kotlin', 'build', '__pycache__'}
LIMIT = 16 * 1024 * 1024
DENIED = {
    '3c922393db6ad533fca11d0e989671548e889084e59922b50d9ec9490137ea0b',
    '886397a887a5cc8abbe58f6f127c88a9c590ec9dc121bbea92659350610772de',
    'b9706e2d4c52fbf2b432cdf44afd064a2a4a9cd585ce0d986bf74dc7148b7e0f',
    'ecb7a93861a36a880dd9723b71161468c70937a9f726503e69587f9e171965bc',
}


def contains_removed_material(raw):
    return any(hashlib.sha256(raw[i:i + 32]).hexdigest() in DENIED for i in range(len(raw) - 31))


def scan(name, raw, findings, counts, depth=0):
    if len(raw) > LIMIT or depth > 4:
        raise ValueError('Audit size/depth limit exceeded: ' + name)
    candidates = [raw]
    # Join source-code string literals to catch concatenated encodings.
    forms = [raw, b''.join(re.findall(rb'[\x22\x27]([^\x22\x27\r\n]*)[\x22\x27]', raw))]
    for text in forms:
        for token in re.findall(rb'(?i)[0-9a-f]{64,}', text):
            if len(token) % 2 == 0:
                candidates.append(bytes.fromhex(token.decode()))
        for token in re.findall(rb'(?i)(?:[0-9a-f]{2}[\s:]+){31,}[0-9a-f]{2}', text):
            candidates.append(bytes.fromhex(re.sub(rb'[\s:]', b'', token).decode()))
        for token in re.findall(rb'(?i)(?:0x[0-9a-f]{1,2}\s*,\s*){31,}0x[0-9a-f]{1,2}', text):
            candidates.append(bytes(int(x, 16) for x in re.findall(rb'(?i)0x([0-9a-f]{1,2})', token)))
        for token in re.findall(rb'(?<!\w)(?:-?\d{1,3}\s*,\s*){31,}-?\d{1,3}(?!\w)', text):
            values = [int(x) for x in re.findall(rb'-?\d+', token)]
            if all(-128 <= x <= 255 for x in values):
                candidates.append(bytes(x & 255 for x in values))
        for token in re.findall(rb'(?<!\w)\d{70,}(?!\w)', text):
            value = int(token)
            candidates.append(value.to_bytes((value.bit_length() + 7) // 8, 'big'))
        for token in re.findall(rb'[A-Za-z0-9+/_-]{40,}={0,2}', text):
            try:
                candidates.append(base64.b64decode(token + b'=' * (-len(token) % 4), altchars=b'-_', validate=True))
            except ValueError:
                pass
    if any(contains_removed_material(data) for data in candidates):
        findings.append({'file': name, 'category': 'removed-original-key-material'})
    stream = io.BytesIO(raw)
    if zipfile.is_zipfile(stream):
        with zipfile.ZipFile(stream) as archive:
            if sum(p.file_size for p in archive.infolist()) > LIMIT:
                raise ValueError('Archive audit limit exceeded: ' + name)
            for entry in archive.infolist():
                if not entry.is_dir():
                    counts['archive_entries'] += 1
                    scan(name + ':' + entry.filename, archive.read(entry), findings, counts, depth + 1)
    elif raw.startswith(b'\x1f\x8b'):
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as compressed:
            scan(name + ':gzip', compressed.read(LIMIT + 1), findings, counts, depth + 1)
    elif raw.startswith(b'\xfd7zXZ\x00'):
        with lzma.LZMAFile(io.BytesIO(raw)) as compressed:
            scan(name + ':xz', compressed.read(LIMIT + 1), findings, counts, depth + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--git-objects', action='store_true')
    parser.add_argument('paths', nargs='*', type=Path)
    args = parser.parse_args()
    findings = []
    counts = {'files': 0, 'archive_entries': 0, 'git_objects': 0}
    for path in args.paths or [ROOT]:
        for file in ([path] if path.is_file() else sorted(path.rglob('*'))):
            if file.is_file() and not any(p in SKIP for p in file.relative_to(path.parent if path.is_file() else path).parts):
                counts['files'] += 1
                scan(str(file), file.read_bytes(), findings, counts)
    refs = []
    if args.git_objects:
        refs = subprocess.check_output(['git', 'for-each-ref', '--format=%(refname) %(objectname)'], cwd=ROOT, text=True).splitlines()
        process = subprocess.Popen(['git', 'cat-file', '--batch-all-objects', '--batch'], cwd=ROOT, stdout=subprocess.PIPE)
        while header := process.stdout.readline():
            oid, kind, size = header.decode().split()
            size = int(size)
            if size > LIMIT:
                raise ValueError('Git object audit limit exceeded: ' + oid)
            raw = process.stdout.read(size)
            if len(raw) != size or process.stdout.read(1) != b'\n':
                raise ValueError('Incomplete Git object: ' + oid)
            counts['git_objects'] += 1
            scan('git:' + oid + ':' + kind, raw, findings, counts)
        if process.wait() != 0:
            raise RuntimeError('Git object enumeration failed')
    print(json.dumps({'status': 'FAIL' if findings else 'PASS', **counts, 'refs': refs, 'findings': findings,
        'limits': 'Targeted removed-key/encoding audit; no server-wide object erasure or general legal clearance is implied.'}, indent=2))
    return bool(findings)


if __name__ == '__main__':
    raise SystemExit(main())
