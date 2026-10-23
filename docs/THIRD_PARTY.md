# Third-party components and notices

This inventory distinguishes the shipped source/tooling from runtime and test
artifacts fetched by Gradle. Exact versions below were resolved by the independent
Maven-only Android consumer; they are not inferred only from declared dependency
versions. The separate review evidence includes the complete resolved coordinates,
artifact hashes and POM license metadata.

## Runtime and tests

| Resolved component | Use | License evidence | Required handling |
| --- | --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib:2.1.20` | Runtime/API | Versioned Maven POM: Apache-2.0 | Preserve copyright/license/NOTICE material when redistributing; project LICENSE includes full Apache text |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2` | Runtime/API | Versioned Maven POM: Apache-2.0 | Preserve upstream license and notices; coroutines BOM 1.10.2 is dependency metadata, not a runtime JAR |
| `org.jetbrains:annotations:23.0.0` | Transitive runtime annotations | Versioned Maven POM: Apache-2.0 | Preserve upstream attribution/license on redistribution |
| `org.bouncycastle:bcprov-jdk15to18:1.86` | Runtime cryptography | Versioned Maven POM and publisher's Bouncy Castle License, MIT-style | Retain the copyright and permission notice; full text in `licenses/BOUNCY-CASTLE.txt` |
| `junit:junit:4.13.2` | Tests only | Versioned POM and JAR's `LICENSE-junit.txt`: EPL-1.0 | Retain EPL terms; if redistributing JUnit binaries, comply with its source-availability obligations; JUnit is not bundled here |
| `org.hamcrest:hamcrest-core:1.3` | Tests only, via JUnit | Versioned parent POM and JAR's `LICENSE.txt`: BSD-3-Clause | Retain copyright, conditions and disclaimer; no endorsement using upstream names |

The two project coordinates are `de.fahrertagebuch:card-core:0.1.0` and
`de.fahrertagebuch:card-usb-android:0.1.0`. Their project-owned code is licensed under Apache-2.0. No original ERCA material
is distributed. They are not independently licensed third parties.

No runtime Maven dependency in this resolved graph is GPL/LGPL. Test-only EPL
components are not copied into production library artifacts. The Bouncy Castle
lightweight API is used without globally registering a JCA provider. No OpenPGP
or MLS artifact is used.

Versioned license metadata:

- https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.pom
- https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.pom
- https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom
- https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.86/bcprov-jdk15to18-1.86.pom
- https://www.bouncycastle.org/licence.html
- https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom
- https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-parent/1.3/hamcrest-parent-1.3.pom

## Build tooling and optional fixture generation

| Component | Scope and terms |
| --- | --- |
| Gradle 8.13 wrapper JAR and launchers | Redistributed upstream wrapper, Apache-2.0; existing copyright headers retained, LICENSE supplied |
| Kotlin Gradle plugin 2.1.20 | Downloaded build tool, Apache-2.0; not bundled |
| Android Gradle plugin 8.9.1 | Downloaded build tool, Apache-2.0; not bundled |
| Android SDK Platform 35 / Build Tools 35.0.0 | Build environment under Android SDK terms; SDK binaries are not distributed |
| Eclipse Temurin JDK 17 | Build environment; OpenJDK GPLv2 with Classpath Exception and upstream component notices; no JDK redistributed |
| `actions/checkout` (v4) and `actions/setup-java` (v5) | CI tools referenced by pinned commit; MIT, upstream [checkout](https://github.com/actions/checkout/blob/v4/LICENSE) / [setup-java](https://github.com/actions/setup-java/blob/v5/LICENSE); no action source or runtime bundled |
| Python `cryptography` / its OpenSSL backend | Optional fixture-generation tools, not invoked for regeneration in final review; separately installed under their upstream licenses, not bundled |

The complete resolved Gradle plugin classpath, including its own transitive tools,
is recorded in the separate review evidence. Those tool binaries are not shipped
or linked into the library runtime; their upstream distribution terms still apply
to anyone redistributing the build environment. This is not a claim that all build
tools have the same license. Missing POM license declarations in that supplementary
inventory are marked explicitly rather than guessed.

## Other material

No original ERCA key is distributed. Application-supplied public recovery material
is described in KEY_MATERIAL.md. Synthetic test certificates, signatures and
records are not trusted certificates. No real public certificate corpus, private
card dump, font file, third-party logo, map asset or original application resource
is distributed. The project SVGs under `docs/assets/` are original project artwork
and covered by the project license; they embed no font or external asset.

Apache-2.0 does not relicense application-supplied keys or third-party components. Retain
NOTICE and the applicable upstream license texts when redistributing this project.
