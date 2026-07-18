# ANEB Probe 0.5.8 release build

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
- Version: `0.5.8` (`versionCode=40`; Debug reports `0.5.8-codex`)
- Room schema: version 19, exported under `app/probe/schemas/`
- Final debug APK: `app/probe/build/outputs/apk/debug/probe-debug.apk`
- Debug APK SHA-256: `B857A8AD2E6CA443CC6B0B60162DE7E5D73A7E4532D9E27F2A92808A83F8DAF0` (`0.5.8-codex`; 61,754,256 bytes; debug artifacts are not release evidence).
- Quality baseline: 541 JVM tests, 0 failures/0 skipped; Android Lint 0 errors (11 dependency/SDK/API notices); 12 measurement-analysis tests, behavior model 31 tests and Go server/gateway tests pass. Catalog contains 8 schemas: internal shared result core, compatible result v1, strict result v2, plus the existing profile/model contracts.
- Device validation: P40 Pro run `019f730f-a0d5-7417-9e01-0866bacdfc57` is the first 0.5.8 `aneb-result-v2` vertical slice: Token Quick completed 3/3 tasks, froze all v2 task-alignment fields and 120 radio samples, scored 97.0/A while retaining `LOW/INCONCLUSIVE`, passed strict v2 with zero errors, and matched independent canonical SHA-256 `bf6bbbbdc6d7d914f1e06384433d52b8cbaa81696e4fda20cea016271117f8b3`. The immutable 27-line historical export now passes 27/27 through the restored compatible v1 validator without rewriting any record; its four digest-mismatch records remain correctly excluded by device integrity export. Mixed v1/v2 device export is still pending while the Claude package owns the shared P40. These are Debug/Quick validation artifacts, not signed release evidence.
