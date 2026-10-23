#!/usr/bin/env python3
"""Review project files and pinned synthetic archives; no external service calls."""
from pathlib import Path
import hashlib, json, re, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', '.gradle', '.kotlin', 'build', '__pycache__'}
ARCHIVES = {
    'card-core/src/test/resources/core-math/vectors.zip':
        '1f1b64a93dc738108d3aa1873e148688615befae85e38d321d963b42b1f7c743',
    'examples/android-consumer/app/src/test/resources/stored-math/vectors.zip':
        'ecf080dd616b8b3d32c4d58796816dfea17bde83d243e36f6c15df2c8812b4ea',
    'card-core/src/test/resources/stored-math/vectors.zip':
        'ecf080dd616b8b3d32c4d58796816dfea17bde83d243e36f6c15df2c8812b4ea',
}
BLOCKED = {'.ddd', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.db', '.sqlite', '.apk'}
RULES = {
    'private-key': rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
    'github-token': rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})',
    'cloud-access-key': rb'(?:AKIA|ASIA)[A-Z0-9]{16}',
    'credential-assignment': rb'(?i)(?:password|passwd|client_secret|api_key)\s*[:=]\s*[\x22\x27][^\x22\x27\s]{6,}',
    'contact-email': rb'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}',
}
# These obvious fixture literals are generated in test code, never sourced from a person.
SYNTHETIC_VIN = b'SYNTHETICVIN00001'
findings = []
files = []
archive_entries = 0

def scan(data, name):
    for label, pattern in RULES.items():
        for match in re.finditer(pattern, data):
            if label == 'contact-email' and match.group().endswith(b'@example.invalid'):
                continue
            findings.append({'file': name, 'category': label})
    for match in re.finditer(rb'[\x22\x27]([A-HJ-NPR-Z0-9]{17})[\x22\x27]', data):
        value = match.group(1)
        if value != SYNTHETIC_VIN and not re.fullmatch(rb'[A-F0-9]+', value):
            findings.append({'file': name, 'category': 'review-vin-like-literal'})

for path in sorted(ROOT.rglob('*')):
    if not path.is_file() or any(part in SKIP for part in path.relative_to(ROOT).parts):
        continue
    name = path.relative_to(ROOT).as_posix()
    if name == 'docs/privacy-audit.json':
        continue
    data = path.read_bytes()
    files.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest()})
    if path.suffix.lower() in BLOCKED or path.name == '.env':
        findings.append({'file': name, 'category': 'forbidden-release-file'})
    if name in ARCHIVES:
        if hashlib.sha256(data).hexdigest() != ARCHIVES[name]:
            findings.append({'file': name, 'category': 'archive-digest-mismatch'})
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            manifest = json.loads(archive.read('manifest.json'))['sha256']
            if set(names) != set(manifest) | {'manifest.json'}:
                findings.append({'file': name, 'category': 'archive-manifest-membership'})
            for entry in names:
                raw = archive.read(entry)
                archive_entries += 1
                if entry != 'manifest.json' and hashlib.sha256(raw).hexdigest() != manifest[entry]:
                    findings.append({'file': name + ':' + entry, 'category': 'entry-digest-mismatch'})
                if Path(entry).name != entry or 'private' in entry.lower():
                    findings.append({'file': name + ':' + entry, 'category': 'unexpected-archive-entry'})
                scan(raw, name + ':' + entry)
    elif name in {'gradle/wrapper/gradle-wrapper.jar', 'examples/android-consumer/gradle/wrapper/gradle-wrapper.jar'}:
        # Gradle's generated wrapper is upstream tooling, not card/user data.
        if hashlib.sha256(data).hexdigest() != '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f':
            findings.append({'file': name, 'category': 'wrapper-digest-mismatch'})
        if not zipfile.is_zipfile(path):
            findings.append({'file': name, 'category': 'invalid-wrapper'})
    else:
        try:
            data.decode('utf-8')
        except UnicodeDecodeError:
            findings.append({'file': name, 'category': 'unexpected-binary'})
        scan(data, name)

report = {'status': 'PASS' if not findings else 'REVIEW_REQUIRED',
          'scope': 'project files excluding build/cache/history and this report',
          'file_count': len(files), 'archive_entries_scanned': archive_entries,
          'findings': findings, 'files': files,
          'limits': 'Pattern and pinned-fixture checks support, but do not replace, source review and owner approval.'}
if len(sys.argv) == 2:
    Path(sys.argv[1]).write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: value for key, value in report.items() if key != 'files'}, indent=2))
sys.exit(bool(findings))
