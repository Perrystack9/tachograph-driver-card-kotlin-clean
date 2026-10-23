"""Independent public/synthetic known answers for every currently stored signed EF.
Private keys exist only in memory. Use a fresh output directory; never replace old vectors.
"""
import argparse, hashlib, json, struct, zipfile
from pathlib import Path
import cryptography
from cryptography.hazmat.backends.openssl.backend import backend
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, ec, padding, utils

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('output', type=Path)
out = p.parse_args().output
if out.exists() and any(out.iterdir()):
    raise RuntimeError('Use a new empty output directory')
out.mkdir(parents=True, exist_ok=True)
def save(name, data): (out / name).write_bytes(data)
def pub(key):
    n = key.public_key().public_numbers()
    return n.n.to_bytes(128, 'big') + n.e.to_bytes(8, 'big')
def certificate(subject, issuer, car, holder, role):
    content = b'\x01' + car + bytes.fromhex('ff544143484f') + bytes([role]) + struct.pack('>I', 1) + holder + pub(subject)
    assert len(content) == 164
    block = b'\x6a' + content[:106] + hashlib.sha1(content).digest() + b'\xbc'
    n = issuer.private_numbers()
    sign = pow(int.from_bytes(block, 'big'), n.d, n.public_numbers.n).to_bytes(128, 'big')
    assert pow(int.from_bytes(sign, 'big'), n.public_numbers.e, n.public_numbers.n).to_bytes(128, 'big') == block
    return sign + content[106:] + car
def tlv(tag, value):
    n = len(value)
    length = bytes([n]) if n < 128 else b'\x81' + bytes([n]) if n < 256 else b'\x82' + n.to_bytes(2, 'big')
    return tag.to_bytes(2 if tag > 255 else 1, 'big') + length + value
def ecc_certificate(key, oid):
    point = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    body = tlv(0x5f29, b'\x00') + tlv(0x42, b'ISSUER00') + tlv(0x5f4c, bytes.fromhex('ff534d52445411'))
    body += tlv(0x7f49, tlv(6, bytes.fromhex(oid)) + tlv(0x86, point)) + tlv(0x5f20, b'SUBJECT0')
    body += tlv(0x5f25, bytes(4)) + tlv(0x5f24, struct.pack('>I', 1))
    # Expired, unauthenticated issuer signature intentionally does not imply certificate trust.
    return tlv(0x7f21, tlv(0x7f4e, body) + tlv(0x5f37, b'\x03' * 96))
def identity():
    name = lambda text: b'\x01' + text.encode().ljust(35, b' ')
    data = b'\x0d' + b'synthetic-own'.ljust(16, b' ') + name('Fixture')
    data += struct.pack('>III', 1577836800, 1577836800, 1893456000)
    data += name('ALPHA') + name('TEST PERSON') + bytes.fromhex('20000229') + b'de'
    assert len(data) == 143
    return data
def application(generation):
    value = bytes([1, 1, 1 if generation == 'g2v2' else 0, 12, 24]) + struct.pack('>HH', 64, 1)
    return value + (b'\x01' if generation == 'g1' else struct.pack('>HHHH', 1, 1, 1, 1))
def originals(generation):
    result = {'0501': application(generation), '0520': identity(), '0504': bytes(68),
        '0505': bytes(33 if generation == 'g1' else 50),
        '0502': bytes((6 if generation == 'g1' else 11) * 12 * 24), '0503': bytes(2 * 24 * 24)}
    if generation == 'g2v2': result['0525'] = struct.pack('>HHHHH', 8, 1, 1, 1, 0)
    return result

root, issuer, card = [rsa.generate_private_key(public_exponent=65537, key_size=1024) for _ in range(3)]
save('test-root.bin', b'ROOTTEST' + pub(root))
save('test-ca.bin', certificate(issuer, root, b'ROOTTEST', b'MSCATEST', 0))
save('test-card.bin', certificate(card, issuer, b'MSCATEST', b'CARDTEST', 1))
save('test-card-wrong.bin', certificate(rsa.generate_private_key(public_exponent=65537, key_size=1024), issuer,
    b'MSCATEST', b'CARDTEST', 1))
for ef, raw in originals('g1').items():
    sig = card.sign(raw, padding.PKCS1v15(), hashes.SHA1())
    card.public_key().verify(sig, raw, padding.PKCS1v15(), hashes.SHA1())
    save(f'rsa-g1-{ef}.raw', raw); save(f'rsa-g1-{ef}.sig', sig)
curves = [('nist256', ec.SECP256R1(), '2A8648CE3D030107', hashes.SHA256(), 32),
    ('bp256', ec.BrainpoolP256R1(), '2B2403030208010107', hashes.SHA256(), 32),
    ('nist384', ec.SECP384R1(), '2B81040022', hashes.SHA384(), 48),
    ('bp384', ec.BrainpoolP384R1(), '2B240303020801010B', hashes.SHA384(), 48),
    ('bp512', ec.BrainpoolP512R1(), '2B240303020801010D', hashes.SHA512(), 64),
    ('nist521', ec.SECP521R1(), '2B81040023', hashes.SHA512(), 66)]
for label, curve, oid, algorithm, width in curves:
    key = ec.generate_private_key(curve)
    save(label + '.cert', ecc_certificate(key, oid))
    save(label + '-wrong.cert', ecc_certificate(ec.generate_private_key(curve), oid))
    for generation in ('g2', 'g2v2'):
        raw_files = originals(generation)
        if generation == 'g2': raw_files['0501-legacy'] = raw_files['0501'][:15]
        if generation == 'g2v2': raw_files['0525-blank'] = bytes(10)
        for ef, raw in raw_files.items():
            for suffix, message in [('', raw), ('-double', hashlib.new(algorithm.name, raw).digest())]:
                der = key.sign(message, ec.ECDSA(algorithm))
                key.public_key().verify(der, message, ec.ECDSA(algorithm))
                r, s = utils.decode_dss_signature(der)
                save(f'{label}-{generation}-{ef}{suffix}.sig', r.to_bytes(width, 'big') + s.to_bytes(width, 'big'))
            save(f'{label}-{generation}-{ef}.raw', raw)
manifest = {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(out.iterdir())}
save('manifest.json', (json.dumps({'generator': 'Python cryptography ' + cryptography.__version__,
    'reference': backend.openssl_version_text(), 'private_keys': 'generated in memory, never written or shipped',
    'sha256': manifest}, indent=2) + '\n').encode())
with zipfile.ZipFile(out / 'vectors.zip', 'x', compression=zipfile.ZIP_DEFLATED) as z:
    for f in sorted(out.iterdir()):
        if f.name == 'vectors.zip': continue
        item = zipfile.ZipInfo(f.name, date_time=(2026, 10, 14, 0, 0, 0))
        item.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(item, f.read_bytes())
print(json.dumps({'binary_vectors': len(manifest), 'archive_sha256': hashlib.sha256((out / 'vectors.zip').read_bytes()).hexdigest()}))

