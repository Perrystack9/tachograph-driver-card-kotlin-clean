# Source history and verification

The public source repository is
[tachograph-driver-card-kotlin-clean](https://github.com/Perrystack9/tachograph-driver-card-kotlin-clean).
It has an independent publication history created from the reviewed, cleaned
source tree. No original FahrerTagebuch commits or previous key-bearing
preparation history are imported. No original ERCA key is distributed.

## Verify a fresh checkout

```sh
git clone https://github.com/Perrystack9/tachograph-driver-card-kotlin-clean.git
cd tachograph-driver-card-kotlin-clean
sha256sum --check SHA256SUMS
git fsck --full --strict
python3 tools/audit-release.py
python3 tools/audit-key-material.py --git-objects
```

The key audit scans decoded Git objects as well as the source, including archives
and common text encodings of the removed key. It stores only non-reversible
rejection digests, never the removed key bytes. These checks complement manual
review; they do not prove the absence of every conceivable encoding or the
erasure of inaccessible server-side objects.

Run the full tests, Android library build, lint and independent Maven-consumer
checks described in [TESTING.md](TESTING.md). The
[verification workflow](https://github.com/Perrystack9/tachograph-driver-card-kotlin-clean/actions/workflows/verify.yml)
performs these checks from a fresh checkout and records their results.

## Distribution scope

Public source availability does not publish a version to a package registry.
The technical version remains `0.1.0-SNAPSHOT`. Maven publication tasks target
only a local development cache. No public package upload or GitHub Release is
part of the verification workflow.

The original private application and earlier preparation repositories are
separate from this repository. Their history must not be merged or imported
here. This project makes no claim that old object URLs in those repositories
have been erased.
