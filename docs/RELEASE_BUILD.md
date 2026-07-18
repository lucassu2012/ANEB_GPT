# ANEB Probe 0.5.10 release build

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
- Version: `0.5.10` (`versionCode=42`; Debug reports `0.5.10-codex`)
- Room schema: version 19, exported under `app/probe/schemas/`
- Final debug APK: `app/probe/build/outputs/apk/debug/probe-debug.apk`
- Debug APK SHA-256: `82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F` (`0.5.10-codex`; 61,850,452 bytes; Android Debug certificate SHA-256 `6644DDCF728B5BC9EFAA07361FC828B9F419D977681000F2E4136C24340B89D9`; debug artifacts are not release evidence).
- Quality baseline: 551 JVM tests in 90 suites, 0 failures/0 errors/0 skipped; Android Lint 0 errors (11 dependency/SDK/API notices); 12 measurement-analysis tests, behavior model 31 tests and Go server/gateway tests pass. Profile catalog 1.3.1 contains 8 schemas, 2 families, 16 profiles, 6 hash-bound runtime bundles, 6 embedded-network profiles and 4 behavior models.
- Device validation: the directly preceding 0.5.9 P40 cellular AI realtime Quick candidate, run `019f7377-9a61-7db5-a8c4-1ac57de1a486`, completed 3/3 turns with 99.8/A and `LOW/INCONCLUSIVE`; its downloaded strict-v2 JSONL passed Schema/identity/digest validation with SHA-256 `FE964695E19997796F5FEB84E05F50FB69F61F2C6299FA0C577263E5198F7EA9`. The exact 0.5.10 binary above adds fail-closed MediaStore cleanup and has passed automated lifecycle fault injection, but has not yet been installed on the shared P40 because Experience Lab has not explicitly released the device. This boundary is intentional; 0.5.9 evidence is not presented as 0.5.10 device evidence. Neither artifact is signed release evidence.
