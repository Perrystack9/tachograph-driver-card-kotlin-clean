# Application-supplied Gen1 recovery keys

## Why no ERCA keys are bundled

This library distributes no original ERCA public key, certificate corpus or
encoded replacement copy. The previous numerical default was removed to avoid
an unresolved redistribution question. The default recovery catalog is empty.
Public keys are not secret credentials; their redistribution terms are a separate
question. The project license does not grant rights to material you obtain elsewhere.

There is no automatic key download, network lookup or key-update service.
Synthetic test keys are retained only as test data and are never defaults.

## What works without keys

All Gen1, Gen2 and Gen2V2 parsing and supported card data remain available.
USB CCID/APDU reads, Android USB Host integration, signature capture and certificate
framing are unchanged. Gen2/Gen2V2 ECDSA mathematics uses the captured signing
certificate and does not require the Gen1 recovery catalog.

Gen1 RSA mathematics for the supported signed EFs (activities, vehicles,
application/holder identification, events and faults) needs an explicit matching
public recovery entry, the captured CA/card certificates, original EF bytes and
signature. With otherwise complete inputs but no matching recovery key, the
result is NOT_CHECKABLE / UNKNOWN_GEN1_RECOVERY_KEY. Other missing-input reasons,
such as NO_GEN1_ISSUER, still apply. See [coverage](COVERAGE.md).

## Official sources

Obtain public material directly from the European Commission Joint Research Centre:

- [Official public-key download](https://dtc.jrc.ec.europa.eu/erca_of_doc/EC_PK.zip)
- [Official ERCA documentation index](https://dtc.jrc.ec.europa.eu/dtc_erca_official_documentation_dt.php.html)

Both links returned HTTP 403 in this review environment on 22 October 2026.
Their current archive contents could not be independently downloaded here.
This is an observation of this environment, not a claim that the service is
unavailable everywhere. No third-party mirror or replacement download was used.

Check the source, applicable terms and any officially published integrity
information yourself. A digest you calculate after download detects later
changes; it does not independently authenticate the download. Never substitute
a synthetic fixture key for an operational public key.

## Supported 144-byte entry

The existing API accepts a numerical Gen1 recovery entry, not a certificate or
PEM/SPKI wrapper. Each entry has exactly this layout:

| Offset | Length | Content |
| --- | --- | --- |
| 0 | 8 | Authority/key reference (CAR), exact octets from the official material |
| 8 | 128 | RSA modulus, unsigned big-endian, exactly 1024 bits and odd |
| 136 | 8 | Public exponent, unsigned big-endian, odd and at least 3, smaller than the modulus |

Inspect a downloaded archive locally. If it contains a documented raw numerical
entry already in this exact format, keep its 144 bytes unchanged. Do not pass
the ZIP itself, a text hex dump or a certificate to the constructor. Do not blindly
truncate a larger file or guess the reference from a filename or country.

If you have separately decoded the official reference, modulus and exponent,
assemble the entry with fixed-width unsigned encoding. For example, this Python
stdlib-only helper validates the structure and creates the bytes; it contains no
key data, download code or certificate decoder:

```python
def encode_gen1_public_key(reference: bytes, modulus: int, exponent: int) -> bytes:
    if len(reference) != 8:
        raise ValueError("The authority reference must be exactly 8 bytes")
    if modulus <= 0 or modulus.bit_length() != 1024 or modulus % 2 != 1:
        raise ValueError("Expected an odd 1024-bit RSA modulus")
    if not (3 <= exponent < modulus) or exponent % 2 != 1 or exponent.bit_length() > 64:
        raise ValueError("Expected an odd public exponent fitting 8 bytes")
    return bytes(reference) + modulus.to_bytes(128, "big") + exponent.to_bytes(8, "big")
```

When converting a signed ASN.1 INTEGER, discard only its sign-padding zero through
a proper positive-number decoder, then encode its unsigned value at the required
width. Left-pad a shorter public exponent with zeros; reject overflow. PEM/SPKI
does not carry the required authority reference by itself. Gen2 keys and arbitrary
certificates are not converted by this helper. If the official record layout
cannot be established, stop rather than guessing.

The Kotlin constructor independently checks 144-byte size, the modulus/exponent
constraints and unique references within the catalog. It copies supplied arrays.
These structural checks do not establish official origin or certificate trust.

## Supply material through the existing API

Your application decides where it stores public recovery material and whether
its terms permit packaging it. The library repository includes no such asset.

```kotlin
// Loaded or assembled by your application from independently checked public material.
val applicationProvidedPublicKey: ByteArray = loadYourPublicRecoveryEntry()
val verifier = CardSignatureMath(
    recoveryKeys = listOf(applicationProvidedPublicKey)
)
val result = verifier.verify(capturedReceipt, capturedCaCertificate)
```

Pass several entries only when their exact references are distinct. Supply the
CA certificate for Gen1 for each supported receipt overload: CardCoreSignature,
CardIdentitySignature or CardEventFaultSignature. The Android consumer's
[compiled example](../examples/android-consumer/app/src/main/kotlin/example/consumer/LibraryUsage.kt)
accepts `recoveryKeys` through those overloads.

`CardSignatureMath()`, `CardSignatureMath(null)` and
`CardSignatureMath(recoveryKeys = emptyList())` all remain valid and use an
empty Gen1 catalog. The caller-supplied API and signature algorithms are unchanged.

## Interpret results

| Status | Meaning |
| --- | --- |
| VALID | The supported signature mathematics matched the complete captured bytes using the supplied public material |
| INVALID | Available supported inputs were checked and the signature mathematics did not match; missing keys do not produce this result |
| NOT_CHECKABLE | A prerequisite is missing, unsupported or unusable; inspect reason, including UNKNOWN_GEN1_RECOVERY_KEY |
| NOT_RUN | No completed mathematical calculation is recorded |

For **every** result, certificate trust remains **NOT_EVALUATED**. Gen1 certificate
message recovery is a mathematical step, not authenticated issuer-chain validation.
No revocation, expiry policy, certified processing or complete DDD conformance
is implied. A valid signature can cover semantically malformed EF contents.
