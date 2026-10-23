# Installation

## Toolchain

- JDK 17 (a full JDK with `javac`, not only a Java runtime)
- Gradle 8.13, pinned by the included wrapper
- Kotlin Gradle plugin 2.1.20
- Android Gradle plugin 8.9.1
- Android compile SDK 35, Build Tools 35.0.0, minimum API 26
- Runtime: coroutines 1.10.2, Bouncy Castle `bcprov-jdk15to18` 1.86
- Tests: JUnit 4.13.2

The older, widely available build toolchain is independent of the original app's
SDK 37 configuration. The reader/parser/crypto production sources are unchanged in this final candidate. No global JCA
provider registration is required.

## Source integration

Copy this project beside your Android app. In the app's `settings.gradle.kts`:

```kotlin
include(":card-core", ":card-usb-android")
project(":card-core").projectDir = file("../driver-card-library/card-core")
project(":card-usb-android").projectDir = file("../driver-card-library/card-usb-android")
```

Use compatible Kotlin/Android Gradle plugin versions in the consumer's plugin
management. If those plugins are already on its classpath, omit duplicate version
requests in the imported module scripts. Add `google()` and `mavenCentral()` to
the consumer's dependency repositories. Then:

```kotlin
dependencies { implementation(project(":card-usb-android")) }
```

The core alone is usable through `implementation(project(":card-core"))` with a
custom `CardReaderTransport`, or with EF bytes that your application already has.
No Android classes are required by the core.

## Local binary integration

Run both `publishToMavenLocal` tasks in the project, then depend on the local Maven
coordinates shown in the README. This writes only to your machine's Maven cache;
there is no remote repository, upload credential or public release configuration.

Retain the generated POM/module metadata. A standalone AAR does not embed the core,
Kotlin, coroutines or Bouncy Castle. Keep their notices when distributing an app.

## Android runtime

The host device needs USB Host support, an OTG connection and an APDU-level USB
CCID reader. The library manifest declares USB Host as optional; make it required
in your own application if it cannot operate without a reader.

A user permission grant is required for each applicable USB attachment. The library
never creates an Activity, permission dialog flow, service, database or UI for you.
Do not invoke card I/O on the main thread. Keep one transport/reader per active
session and serialize direct transport access. Cancellation and reader failures
must be handled by the caller.
