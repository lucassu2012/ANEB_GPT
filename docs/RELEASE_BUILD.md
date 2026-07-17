# ANEB Probe 0.5.6 release build

## Ownership boundary

The release signing key is a Product Owner asset. It must be created and backed up outside this repository. The repository stores no keystore or password, and release packaging fails closed when credentials are missing.

Required environment variables:

- `ANEB_RELEASE_STORE_FILE`: absolute path to the `.jks`/`.keystore` file
- `ANEB_RELEASE_STORE_PASSWORD`
- `ANEB_RELEASE_KEY_ALIAS`
- `ANEB_RELEASE_KEY_PASSWORD`

Equivalent Gradle properties are `aneb.release.storeFile`, `aneb.release.storePassword`, `aneb.release.keyAlias`, and `aneb.release.keyPassword`. Do not commit them to `gradle.properties`.

## Build and verify

From `app/`:

```powershell
.\gradlew.bat :probe:verifyReleaseSigning :probe:bundleRelease
```

For a directly installable signed APK:

```powershell
.\gradlew.bat :probe:assembleRelease
```

Before distribution, verify the certificate owner and SHA-256 fingerprint with Android `apksigner verify --print-certs`, then retain the fingerprint with the release evidence. Losing the signing key prevents seamless updates; leaking it compromises every future release.

## Network trust boundary

- All release traffic is HTTPS; cleartext is globally disabled.
- `120.79.148.0` additionally trusts the project-owned IP-SAN CA required by the D-22/D-25 SNI-RST measurement channel.
- The bundled CA certificate is public material, expires in October 2028, and must be rotated together with the E-01 server certificate before expiry.
- Debug-only local cleartext and local CA trust remain under `src/debug` and do not enter release builds.

## Current artifacts

- Debug package: `com.aneb.probe.codex`
- Release package: `com.aneb.probe`
- Version: `0.5.6` (`versionCode=38`; Debug reports `0.5.6-codex`)
- Room schema: version 19, exported under `app/probe/schemas/`
- Final debug APK: `app/probe/build/outputs/apk/debug/probe-debug.apk`
- Debug APK SHA-256: `B2C56295DA3565B06D71C19523C5685DBD1514C2B67E66C88C86D005565F6E57` (`0.5.6-codex`; debug artifacts are not release evidence).
- Quality baseline: 525 JVM tests, 0 failures/0 skipped; Android Lint 0 errors (11 dependency/SDK/API notices); TTFT repeatability analyzer 5 tests, behavior model 31 tests and Go server/gateway tests pass.
- Device validation: P40 Pro Token/AI realtime/network Quick envelopes passed Draft 2020-12 validation, independent Python canonical-digest verification and 1Hz radio evidence count reconciliation. A 5-run Token Quick cohort additionally passed task-aligned TTFT repeatability with median CV 1.425% and maximum CV 4.986% against a 10% limit. These are Debug/Quick validation artifacts, not signed release evidence.
