from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa, ec, padding, utils
from cryptography.hazmat.primitives import hashes, serialization
import hashlib, json, struct, cryptography
import argparse
args=argparse.ArgumentParser(description='Generate independent synthetic public test vectors; never stores private keys.')
args.add_argument('output',type=Path,help='New empty output directory')
options=args.parse_args()
out=options.output
if out.exists() and any(out.iterdir()): raise RuntimeError('Use a new empty output directory')
out.mkdir(parents=True, exist_ok=True)
def save(name, data): (out/name).write_bytes(data)
def pub(k):
    v=k.public_key().public_numbers()
    return v.n.to_bytes(128,'big')+v.e.to_bytes(8,'big')
def certificate(subject, issuer, car, chr, role, cpi=1, inner=None, aid=bytes.fromhex("ff544143484f"), head=0x6a, tail=0xbc, public_override=None):
    content=bytes([cpi])+(inner or car)+aid+bytes([role])+struct.pack('>I',1)+chr+(public_override or pub(subject))
    assert len(content)==164
    em=bytes([head])+content[:106]+hashlib.sha1(content).digest()+bytes([tail])
    n=issuer.private_numbers()
    sig=pow(int.from_bytes(em,'big'),n.d,n.public_numbers.n).to_bytes(128,'big')
    assert pow(int.from_bytes(sig,'big'),n.public_numbers.e,n.public_numbers.n).to_bytes(128,'big')==em
    return sig+content[106:]+car
root=rsa.generate_private_key(public_exponent=65537,key_size=1024)
issuer=rsa.generate_private_key(public_exponent=65537,key_size=1024)
card=rsa.generate_private_key(public_exponent=65537,key_size=1024)
car=b'ROOTTEST';mref=b'MSCATEST';cref=b'CARDTEST'
save('test-root.bin',car+pub(root))
save('test-ca.bin',certificate(issuer,root,car,mref,0))
save('test-card.bin',certificate(card,issuer,mref,cref,1))
for label,kwargs in [('cpi',{'cpi':2}),('inner-car',{'inner':b'OTHERREF'}),('aid',{'aid':bytes(6)}),('role',{'role':0}),('header',{'head':0x4a}),('trailer',{'tail':0xcc}),('modulus',{'public_override':b'\x7f'+pub(card)[1:]}),('exponent',{'public_override':pub(card)[:128]+(2).to_bytes(8,'big')})]:
    save('test-card-bad-'+label+'.bin',certificate(card,issuer,mref,cref,kwargs.pop('role',1),**kwargs))
for kind,raw in [('activity',bytes(68)),('vehicles',bytes(33))]:
    sig=card.sign(raw,padding.PKCS1v15(),hashes.SHA1())
    card.public_key().verify(sig,raw,padding.PKCS1v15(),hashes.SHA1())
    save('rsa-'+kind+'.raw',raw);save('rsa-'+kind+'.sig',sig)
    # An independently signed malformed encoded block: verifier must not accept a suffix scan.
    em=b'\x00\x01'+b'\xff'*90+b'\x00'+bytes.fromhex('3021300906052b0e03021a05000414')+hashlib.sha1(raw).digest()
    assert len(em)==128
    for label,bad in [('bad-padding',em[:4]+b'\x00'+em[5:]),('bad-oid',em[:102]+bytes([em[102]^1])+em[103:]),('bad-suffix',em[:-1]+bytes([em[-1]^1])),('missing-null',b'\x00\x01'+b'\xff'*92+b'\x00'+bytes.fromhex('301f300706052b0e03021a0414')+hashlib.sha1(raw).digest())]:
        nums=card.private_numbers()
        save('rsa-'+kind+'-'+label+'.sig',pow(int.from_bytes(bad,'big'),nums.d,nums.public_numbers.n).to_bytes(128,'big'))
def tlv(tag, value):
    t=tag.to_bytes(2 if tag>255 else 1,'big');n=len(value)
    l=bytes([n]) if n<128 else b'\x81'+bytes([n]) if n<256 else b'\x82'+n.to_bytes(2,'big')
    return t+l+value
curves=[('nist256',ec.SECP256R1(),'2A8648CE3D030107',hashes.SHA256(),32),
 ('bp256',ec.BrainpoolP256R1(),'2B2403030208010107',hashes.SHA256(),32),
 ('nist384',ec.SECP384R1(),'2B81040022',hashes.SHA384(),48),
 ('bp384',ec.BrainpoolP384R1(),'2B240303020801010B',hashes.SHA384(),48),
 ('bp512',ec.BrainpoolP512R1(),'2B240303020801010D',hashes.SHA512(),64),
 ('nist521',ec.SECP521R1(),'2B81040023',hashes.SHA512(),66)]
for name,curve,oid,h,width in curves:
    key=ec.generate_private_key(curve)
    point=key.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
    body=tlv(0x5f29,b'\x00')+tlv(0x42,b'ISSUER00')+tlv(0x5f4c,bytes.fromhex('ff534d52445411'))
    body+=tlv(0x7f49,tlv(6,bytes.fromhex(oid))+tlv(0x86,point))+tlv(0x5f20,b'SUBJECT0')+tlv(0x5f25,bytes(4))+tlv(0x5f24,(1).to_bytes(4,'big'))
    # Deliberately expired, unauthenticated issuer signature with a different curve/length.
    save(name+'.cert',tlv(0x7f21,tlv(0x7f4e,body)+tlv(0x5f37,b'\x03'*96)))
    save(name+'.point',point)
    for kind,raw in [('activity',bytes(68)),('vehicles',bytes(50))]:
        for suffix,algo,message in [('',h,raw),('-double',h,hashlib.new(h.name,raw).digest())]:
            der=key.sign(message,ec.ECDSA(algo));key.public_key().verify(der,message,ec.ECDSA(algo))
            r,s=utils.decode_dss_signature(der)
            save(name+'-'+kind+suffix+'.sig',r.to_bytes(width,'big')+s.to_bytes(width,'big'))
        save(name+'-'+kind+'.raw',raw)
    # A valid fixed-width plain R starting 30 must never be mistaken for DER.
    if name=='nist256':
        for i in range(8192):
            der=key.sign(bytes(68),ec.ECDSA(h));r,s=utils.decode_dss_signature(der)
            raw=r.to_bytes(width,'big')+s.to_bytes(width,'big')
            if raw[0]==0x30:
                key.public_key().verify(der,bytes(68),ec.ECDSA(h));save('nist256-plain30.sig',raw);break
        else:raise RuntimeError('no 30-prefix sample')
def mixed_curve_vectors():
    subject=ec.generate_private_key(ec.SECP256R1())
    authority=ec.generate_private_key(ec.BrainpoolP384R1())
    point=subject.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
    body=tlv(0x5f29,b'\x00')+tlv(0x42,b'ISSUER00')+tlv(0x5f4c,bytes.fromhex('ff534d52445411'))
    body+=tlv(0x7f49,tlv(6,bytes.fromhex('2A8648CE3D030107'))+tlv(0x86,point))+tlv(0x5f20,b'SUBJECT0')+tlv(0x5f25,bytes(4))+tlv(0x5f24,(1).to_bytes(4,'big'))
    encoded=tlv(0x7f4e,body)
    der=authority.sign(encoded,ec.ECDSA(hashes.SHA384()))
    authority.public_key().verify(der,encoded,ec.ECDSA(hashes.SHA384()))
    r,v=utils.decode_dss_signature(der)
    save('mixed.cert',tlv(0x7f21,encoded+tlv(0x5f37,r.to_bytes(48,'big')+v.to_bytes(48,'big'))))
    save('mixed-issuer.point',authority.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint))
    for kind,raw in [('activity',bytes(68)),('vehicles',bytes(50))]:
        der=subject.sign(raw,ec.ECDSA(hashes.SHA256()))
        subject.public_key().verify(der,raw,ec.ECDSA(hashes.SHA256()))
        r,v=utils.decode_dss_signature(der)
        save('mixed-'+kind+'.raw',raw);save('mixed-'+kind+'.sig',r.to_bytes(32,'big')+v.to_bytes(32,'big'))
mixed_curve_vectors()
def semantic_invalid_vectors():
    subject=ec.generate_private_key(ec.SECP256R1())
    point=subject.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
    body=tlv(0x5f29,b'\x00')+tlv(0x42,b'ISSUER00')+tlv(0x5f4c,bytes.fromhex('ff534d52445411'))
    body+=tlv(0x7f49,tlv(6,bytes.fromhex('2A8648CE3D030107'))+tlv(0x86,point))+tlv(0x5f20,b'SUBJECT0')+tlv(0x5f25,bytes(4))+tlv(0x5f24,(1).to_bytes(4,'big'))
    save('semantic.cert',tlv(0x7f21,tlv(0x7f4e,body)+tlv(0x5f37,b'\x03'*96)))
    for kind,size in [('activity',68),('vehicles',50)]:
        raw=b'\xff\xff'+bytes(size-2)  # Complete EF, impossible pointer; not a valid semantic projection.
        der=subject.sign(raw,ec.ECDSA(hashes.SHA256()))
        subject.public_key().verify(der,raw,ec.ECDSA(hashes.SHA256()))
        r,v=utils.decode_dss_signature(der)
        save('semantic-'+kind+'.raw',raw);save('semantic-'+kind+'.sig',r.to_bytes(32,'big')+v.to_bytes(32,'big'))
semantic_invalid_vectors()
manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir()) if p.suffix!='.json'}
(out/'manifest.json').write_text(json.dumps({'generator':'Python cryptography '+cryptography.__version__,'reference':'OpenSSL backend; RSA certificate framing checked via Python integer public exponentiation','private_keys':'generated in memory, never written or shipped','sha256':manifest},indent=2)+'\n')
print('Independent public/synthetic vectors:',len(manifest))

