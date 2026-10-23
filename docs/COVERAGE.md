# Implemented coverage

Coverage describes the extracted code and synthetic tests. It is not a claim that
all possible card variants, records or commercial readers have been validated.
Unsupported generations, sizes and capacities are rejected explicitly.

| Data family | Gen1 | Gen2 | Gen2V2 |
| --- | --- | --- | --- |
| Application / holder identification | Yes | Yes | Yes, including application V2 |
| Activities (0504), vehicles (0505) | Yes | Yes | Yes |
| Controls, events, faults, specific conditions | Yes | Yes | Yes |
| Work-period places | Yes | Yes | Yes |
| GNSS accumulated-driving positions | No | Yes | Yes |
| GNSS and places authentication records | No | No | Yes |
| Border crossings, load/unload, load types | No | No | Yes |
| Vehicle units used | No | Yes | Yes |
| Driving-licence / usage / last-download metadata | Yes | Yes | Yes |
| ICC / IC identification, MF directory and advertised communication data | Implemented where present | Implemented where present | Implemented where present |
| Certificate structural decoding | Gen1 framing | Gen2 framing | Gen2 framing |

The decoder enforces fixed supported layouts and uses capacities from application
identification. The parser classes expose the exact supported record counts and
lengths. A parsed record can contain preserved unknown raw codes. This is not a
claim that every value has a higher-level semantic interpretation.

## Signature mathematics

| EF | Gen1 | Gen2 | Gen2V2 |
| --- | --- | --- | --- |
| 0504 activities / 0505 vehicles | Yes | Yes | Yes |
| 0501 application / 0520 identification | Yes | Yes | Yes |
| 0502 events / 0503 faults | Yes | Yes | Yes |
| 0525 application V2 | No | No | Conditional on a complete supported V2 capture |
| Other EFs | No | No | No |

Gen1 verification requires a supported public recovery key and matching CA/card
certificates. The default catalog is empty; applications supply public Gen1
recovery entries. No original ERCA keys are included. There is no key-update
service or trusted certificate store. With otherwise complete inputs, a missing
Gen1 recovery key gives NOT_CHECKABLE / UNKNOWN_GEN1_RECOVERY_KEY rather than
INVALID. All parsing/reading and Gen2/V2 mathematics remain available without
Gen1 keys. See [key provisioning](KEY_MATERIAL.md).

Gen2/V2 supports six fixed ECDSA domains. A valid public point and file signature
do not authenticate the certificate issuer. Certificate trust is never evaluated.

## Explicit exclusions

- Complete DDD file container parsing, generation, archival/export compliance
- Vehicle-unit download protocols or company/workshop/control card products
- A complete catalogue of every historical/national/proprietary layout
- Certificate trust chains, revocation, expiry policy, legal certification
- Driving/rest infringement calculations, inferred rest, UI, reporting
- Database, profile matching, cloud, telemetry and application persistence
- Host-side T=0/T=1 TPDU/character protocols and universal reader compatibility

No physical reader matrix or Android emulator campaign was performed for this
extraction. Existing behavior was preserved and exercised through synthetic JVM
transport scripts and offline crypto known answers.
