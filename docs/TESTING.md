# Testing

## Required checks

```sh
./gradlew :card-core:test :card-usb-android:testReleaseUnitTest \
  :card-usb-android:assembleRelease :card-usb-android:lintRelease
python3 tools/audit-release.py
```

The JVM core can be tested without an Android SDK:

```sh
./gradlew -PcoreOnly :card-core:test
```

The core tests cover EF framing/capacities, cyclic activity and vehicle records,
malformed pointers and byte offsets, metadata/text decoding, empty/unknown values,
optional EF failures, application isolation, scripted APDU retries/chaining,
CCID descriptors/framing, defensive buffers and cancellation/cleanup.

Cryptographic tests cover positive and negative RSA recovery and PKCS#1 blocks,
six ECDSA domains, complete original bytes, tampering, unsupported policy/key
cases, plain versus DER signatures, double hashing, source binding and unchanged
JCA provider registration. Certificate trust stays NOT_EVALUATED, including for
mathematically valid signatures with an unauthenticated certificate.

`card-usb-android` integration tests compile in a separate module, with no access
to core `internal` declarations. They verify that the public API works for every
implemented signed EF, supported generation and permitted curve. They are local
JVM tests; they do not attach a USB device.

## Synthetic resources

`core-math/vectors.zip`: 83 binary known answers plus SHA-256 manifest. Real public
JRC root/MSCA fixtures were removed during extraction; every remaining entry is
synthetic. Original entry bytes remain unchanged.

`stored-math/vectors.zip`: 298 binary known answers plus SHA-256 manifest, unchanged
from the baseline. Archive SHA-256:
`ecf080dd616b8b3d32c4d58796816dfea17bde83d243e36f6c15df2c8812b4ea`.

The baseline generators signed and independently checked the vectors using
Python cryptography/OpenSSL. The scripts under `tools/` preserve that method,
without saving private keys. Fresh random keys produce new answers, not identical
archives. Do not regenerate pinned fixtures just to make a failing test pass.

Certificate framing samples built by `CertificateFixtures` are explicitly
synthetic and intentionally do not authenticate an issuer.

## Limits

Passing these checks demonstrates the extracted build and exercised behavior,
not complete format compliance, real-world card/reader universality or legal
correctness. No emulator, performance campaign, real-user card dump, real GNSS
track or physical USB reader matrix is part of this extraction.

The separate final review report records actual run results. License and key
redistribution gates are documented in LICENSE_REVIEW.md and KEY_MATERIAL.md.

Run the independent Maven-only Android consumer as described in
`examples/android-consumer/README.md`. It is a separate Gradle build and has no
project dependencies on the library modules.

## GitHub Actions

The single `Library verification` workflow uses a fresh Ubuntu 24.04 checkout,
Java 17 and the runner's Android SDK 35. It runs the existing tests and lint,
publishes only to an isolated temporary Maven directory, and builds/tests the
independent consumer through those Maven coordinates. The final step checks
455/4/7 test counts, zero skipped tests and both modules' POM/module/source
metadata. Actual results are recorded in each run; no hardware test is implied.
The workflow has read-only repository permissions and no public publishing step.

## Explicit-key and clean-history regressions

Eight additional core tests cover the empty default/null/empty catalogs, missing
and unknown Gen1 keys, explicit synthetic keys across signed families, tampering,
all six curves for both Gen2 versions, and malformed/duplicate entries. Two new
Maven-consumer tests compile and exercise the documented example with and without
an explicitly provided synthetic key. No pre-existing test or fixture is removed
or weakened: 455 core, 4 USB-module JVM and 7 consumer tests are expected.

```sh
python3 tools/audit-key-material.py --git-objects
git fsck --full --strict
```

The targeted key audit decodes Git objects and common raw, hex, base64, numeric
and ZIP/gzip/xz forms. It retains only rejection digests of removed key segments,
never the key itself. It complements source review; it is not an assurance against
every conceivable encoding or GitHub's inaccessible server-side object storage.
The workflow scans the isolated Maven artifacts too and records lint error and
warning counts. See [source history and verification](RELEASE_PREPARATION.md)
for fresh-checkout checks.
