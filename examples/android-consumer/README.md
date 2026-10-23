# Independent Android consumer

Example for the Driver Card Parser & Android USB Library. Preserve applicable [LICENSE](../../LICENSE) and
[NOTICE](../../NOTICE) material when redistributing the library.

This is a standalone Android build. It uses no `includeBuild`, sibling project
reference, source directory from the library, or original application component.
It depends on both libraries only by their documented Maven coordinates:

```kotlin
implementation("de.fahrertagebuch:card-core:0.1.0-SNAPSHOT")
implementation("de.fahrertagebuch:card-usb-android:0.1.0-SNAPSHOT")
```

## Reproduce

Install JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. First, in the library
root, build, test and publish both modules to a local Maven cache:

```sh
./gradlew :card-core:test :card-usb-android:testReleaseUnitTest \
  :card-usb-android:assembleRelease :card-usb-android:lintRelease \
  :card-core:publishToMavenLocal :card-usb-android:publishToMavenLocal
```

Then, from this standalone directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:integrationDependencies
```

For a completely isolated Maven cache, pass the same absolute
`-Dmaven.repo.local=/path/to/empty-cache` argument to both commands. The consumer's
`mavenLocal()` repository is restricted to the `de.fahrertagebuch` group. No
public upload repository or publishing credentials are configured.

`LibraryUsage` compiles the documented Android transport/permission/read APIs,
card identity and activity/vehicle parsers, and all three signature verification
overloads. Local JVM tests exercise the existing synthetic signature matrix,
invalid signatures, missing Gen1 issuer, absent reader and synthetic data parsing.

## Public Gen1 recovery keys

No original ERCA key is bundled or fetched. `LibraryUsage.verify(receipt, issuer)`
uses an empty catalog; otherwise complete Gen1 inputs return NOT_CHECKABLE with
UNKNOWN_GEN1_RECOVERY_KEY. Supply your own validated public 144-byte entry:

```kotlin
val result = LibraryUsage.verify(receipt, issuer,
    recoveryKeys = listOf(applicationProvidedPublicKey))
```

See [official sources and the byte layout](../../docs/KEY_MATERIAL.md). The two
additional consumer tests check this documented path without keys and with an
explicit synthetic key. All seven consumer tests are local JVM tests; no actual
ERCA material is stored in the example. Gen2/V2 does not need this Gen1 catalog.

The test fixture archive is a physical copy of the library's synthetic stored-math
known answers, with unchanged SHA-256
`ecf080dd616b8b3d32c4d58796816dfea17bde83d243e36f6c15df2c8812b4ea`.
It is test-only; it is not packaged into the example's production APK.

This minimal example application has no Activity or UI. Its purpose is a
binary integration/build check. No USB device, Android permission dialog, emulator
or physical-reader test is executed by the local JVM tests.
