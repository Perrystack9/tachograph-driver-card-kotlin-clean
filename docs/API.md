# API guide

See [license and attribution](LICENSE_REVIEW.md) for redistribution notices.

All library APIs use `de.fahrerabrechnung.data.card`. Source KDoc and typed
signatures are the detailed reference. This guide describes the supported entry
points; lower-level classes remain available where the original code exposed them.

## Reader and transport

| API | Contract |
| --- | --- |
| `AndroidCcidTransport(context, slot = 0)` | USB Host CCID transport; no automatic permission request |
| `devices()`, `selectDevice(deviceId)` | Enumerate candidate interfaces and pin the intended USB attachment |
| `requestPermission(PendingIntent)` | Ask Android for a compatible device grant; returns whether a request was made |
| `onPermissionResult(device, granted)` | Record the permission callback for the attachment |
| `CardReaderTransport` | Custom transport boundary: availability, open, power on, exchange, power off, close |
| `DriverCardReader.readApplications()` | One powered read of both fixed applications plus shared master-file captures |
| `DriverCardReader.read()` | Single-application compatibility entry point, preferring Gen2 |
| `CardDiagnosticProbe.inspect()` | Explicit Android hardware probe; no holder identity or APDU dump |

A reader serializes its captures. Direct access to a transport is not a general
thread-safe API. Use one session at a time and run reads on `Dispatchers.IO`.
Each read attempts power-off and close in `finally`. Do not swallow coroutine
cancellation or retry indefinitely when a card is removed.

The transport accepts short/extended **APDU-level CCID** devices. TPDU/character
exchange readers are rejected. USB endpoints, message lengths and chaining are
bounded; this does not imply compatibility with every reader or Android device.

## Permission integration

Declare USB Host support in the host manifest. Register a receiver owned by your
Activity/service before calling `requestPermission`. A minimal intent setup is:

```kotlin
val action = context.packageName + ".USB_CARD_PERMISSION"
val intent = android.content.Intent(action).setPackage(context.packageName)
val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
    if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
val permissionIntent = android.app.PendingIntent.getBroadcast(context, 0, intent, flags)
val transport = AndroidCcidTransport(context)
transport.requestPermission(permissionIntent)
```

Use a receiver restricted to your app (`Context.RECEIVER_NOT_EXPORTED` on API 33+
for dynamic registration). Filter the exact action, extract `UsbManager.EXTRA_DEVICE`
and `UsbManager.EXTRA_PERMISSION_GRANTED`, then call
`transport.onPermissionResult(device, granted)`. On API 33+ use the typed
`getParcelableExtra(name, UsbDevice::class.java)` overload; on older versions use
the legacy overload. Only start a worker read after a successful grant. Unregister
the receiver with its owning lifecycle. No receiver or background worker is
installed automatically by the library.

If several readers are attached, first use `devices()` and `selectDevice()` so
permission and the read refer to the same attachment. The host app handles USB
detach events and its own cancellation/retry policy.

## Results and decoding

`CardApplicationReadBatch.applications` contains Gen1 and Gen2 attempts. Inspect
`status` (`READ`, `ABSENT`, `READ_FAILED`) before using `result`.
`identityAgreement` describes identity agreement between the applications; it is
neither authorization to retain a person's data nor certificate trust.

`CardReadResult` contains recognition, generation, holder/card identity, optional
EF captures, failure flags, certificates and typed signature receipts. A missing
file is not the same as an empty parsed file. Respect both the nullable field and
its failure flag. Inspect `coreReads` when original capture succeeded but a
semantic parser rejected a pointer or record.

| Input | Decoder |
| --- | --- |
| Application/identification bytes | `DriverCardDecoder`, `CardIdentitySourceParser` |
| Activities / vehicles | `CardActivityParser`, `CardVehiclesParser` |
| Controls / events / faults | `CardControlParser`, `CardEventFaultParser` |
| Specific conditions | `CardSpecificConditionParser` |
| GNSS positions / authentication | `CardGnssAccumulatedDrivingParser`, `CardGnssAuthenticationParser` |
| Work-period places / authentication | `CardPlacesParser`, `CardPlacesAuthenticationParser` |
| Border crossings / load-unload / load type | `CardBorderCrossingsParser`, `CardLoadUnloadParser`, `CardLoadTypeParser` |
| Vehicle units | `CardVehicleUnitsParser` |
| Licence/current usage/download metadata | `CardMetadataParser` |
| ICC / IC identification | `CardMasterFileParser` |
| Certificates / directory / communication | `CardCertificateParser`, `CardDirectoryParser`, `CardCommunicationParser` |

Construct the corresponding typed `*File` from a complete EF plus its generation
and recorded capacity where required. These APIs consume **EF contents**, not an
arbitrary DDD container. `ParseError` carries offset, field and reason. Do not
repair malformed data silently, infer missing rest periods or join records into
an asserted road route. Unknown raw values remain raw where supported.

Byte arrays may contain sensitive originals. Several types copy on construction
or return frozen snapshots; ordinary `CardActivityFile.dailyRecords` and
`CardVehiclesFile.records` are still mutable arrays. Treat returned buffers as
read-only, or copy them before application-side manipulation.

## Offline signature mathematics

```kotlin
// For Gen1, supply lawfully obtained public recovery material from your app.
val math = CardSignatureMath(recoveryKeys = listOf(applicationProvidedPublicKey))
val verdict = math.verify(receipt, issuerCertificate)
when (verdict.status) {
    CardSignatureMathStatus.VALID -> { /* signature mathematics matched */ }
    CardSignatureMathStatus.INVALID -> { /* mathematics did not match */ }
    CardSignatureMathStatus.NOT_CHECKABLE -> { /* inspect verdict.reason */ }
    CardSignatureMathStatus.NOT_RUN -> { /* no completed calculation */ }
}
```

There are overloads for `CardCoreSignature`, `CardIdentitySignature` and
`CardEventFaultSignature`. Pass a captured receipt including its exact original
bytes, signature and signing certificate. Gen1 also needs the CA certificate.
No database entities or source-ID reconstruction are needed.

`CardSignatureMath()` and `CardSignatureMath(null)` use an empty Gen1 recovery
catalog, as does an explicitly empty list. No original ERCA key is bundled or
automatically downloaded. With otherwise complete Gen1 inputs and an unknown
recovery reference, the result is `NOT_CHECKABLE / UNKNOWN_GEN1_RECOVERY_KEY`;
missing keys never establish mathematical invalidity. Other missing-input reasons
(such as `NO_GEN1_ISSUER`) still take precedence where applicable.

`CardSignatureMath(recoveryKeys = listOf(publicKeyBytes))` supplies the catalog.
Each entry is reference 8 bytes + RSA modulus 128 bytes + exponent 8 bytes,
unsigned big-endian. Entries are copied and validated; duplicate references are
rejected. Do not pass private keys, PEM, SPKI or a certificate as this byte format.
See [key provisioning](KEY_MATERIAL.md) for official sources, conversion,
structural validation and the meanings of VALID / INVALID / NOT_CHECKABLE.

Supported mathematics: Gen1 RSA-1024 PKCS#1 v1.5/SHA-1 and strict Gen1 certificate
message recovery; Gen2/V2 plain fixed-width ECDSA using the six existing policies
(NIST P-256/P-384/P-521 and brainpool P256r1/P384r1/P512r1). The original policy
selects the hash and width; DER signatures and arbitrary curves are not accepted.

`certificateTrust` always returns `NOT_EVALUATED`. There is no issuer-chain trust,
revocation lookup or expiry-policy result. `CAPTURED_UNVERIFIED` describes capture,
not a mathematical verdict. A mathematically valid original can still contain
semantically invalid EF data. Only the EF families in COVERAGE.md are verified.

The new facade labels provenance IDs `in-memory` and returns byte digests. These
IDs are placeholders, not persisted source references. Bind results to your own
immutable records if your application stores them. The legacy result `matches`
methods expect their legacy IDs and should not be used as a persistence binding
for facade results. The facade never saves or changes receipt status.
