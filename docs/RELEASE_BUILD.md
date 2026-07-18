# ANEB Probe 0.5.10 release build

## Ownership boundary

The release signing key is a Product Owner asset. It must be created and backed up outside this repository. The repository stores no keystore or password, and release packaging fails closed when credentials are missing.

Required environment variables:

- `ANEB_RELEASE_STORE_FILE`: absolute path to the `.jks`/`.keystore` file
- `ANEB_RELEASE_STORE_PASSWORD`
- `ANEB_RELEASE_KEY_ALIAS`
- `ANEB_RELEASE_KEY_PASSWORD`

Equivalent Gradle properties are `aneb.release.storeFile`, `aneb.release.storePassword`, `aneb.release.keyAlias`, and `aneb.release.keyPassword`. Do not commit them to `gradle.properties`.

Run `python scripts/scan_repository_secrets.py` before committing. The local quality gate and cloud candidate workflow also enforce it. A token pasted into chat or another durable collaboration record must be revoked even if the repository scan is clean; see `SECURITY.md`.

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
- Quality baseline: 551 JVM tests in 90 suites, 0 failures/0 errors/0 skipped; Android Lint 0 errors (11 dependency/SDK/API notices); 12 measurement/result tests, 8 candidate-packaging tests, 6 credential-safety tests, behavior model 31 tests and Go server/gateway tests pass. Profile catalog 1.3.1 contains 8 schemas, 2 families, 16 profiles, 6 hash-bound runtime bundles, 6 embedded-network profiles and 4 behavior models.
- Device validation: the exact cloud Debug candidate SHA-256 `49244B3157FCC47D54EDA61A51EAF4B69A71BD2B95314BAE54E327CE8B0F6D85` was installed on the P40 as `0.5.10-codex` / versionCode 42. Because its Debug signer differed from the previous package, validation used a controlled backup-uninstall-install-restore flow rather than an in-place Android upgrade; the Room schema remained v19, integrity check was OK, and 36 `result_envelope` / 10 `test_run` rows were preserved. Secure preferences/API keys were intentionally not restored. The mixed export contained 32 verified documents (v1=27, v2=5; 32 unique run IDs, no duplicates) and transparently rejected four integrity failures; the single v2 export for run `019f7377-9a61-7db5-a8c4-1ac57de1a486` matched the batch row byte-for-byte. Both MediaStore rows had `is_pending=0`. This is controlled Debug evidence, not signed Release or non-ADB installation evidence.

## Cloud Debug candidate

Pushes to `main` and `codex/**` run the full cloud gate before an installable candidate is exposed. The Android candidate job waits for the tracked-source credential scan, Profile/result/packaging contracts, Go server, dedicated gateway and behavior-model jobs; it then runs Android tests/Lint/build, verifies the Debug/Release component boundary, and packages the exact APK with:

- `build-manifest.json`: package, version, SDK, signer, file size, SHA-256 and source workflow identity;
- `checksums.sha256`: APK, manifest and Chinese install instructions;
- `ANEB-安装说明.txt`: non-ADB file-manager installation and mismatch stop rules.

Non-PR builds also create a GitHub artifact provenance attestation. The downloadable workflow artifact is retained for 30 days. It is explicitly `debug_non_release`; it may be used for controlled P40 validation but is not a signed public Release.

Verified cloud example (2026-07-18):

- Workflow run: [`29635434193`](https://github.com/lucassu2012/ANEB_GPT/actions/runs/29635434193), source `51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2`; all six jobs passed, including `Tracked-source credential scan`.
- Artifact ID/name: `8427011992` / `aneb-probe-debug-51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2`; 24,842,612 bytes; expires `2026-08-17T07:24:33Z`.
- Artifact ZIP digest: `sha256:ffef2b3f0c3177e3ac81794b3d7ced536eee3afae71f5927e6a43fd6db3cccb0`.
- Cloud APK: SHA-256 `49244B3157FCC47D54EDA61A51EAF4B69A71BD2B95314BAE54E327CE8B0F6D85`; package `com.aneb.probe.codex`; `versionCode=42`; `versionName=0.5.10-codex`; Android Debug signing identity verified.
- Provenance: GitHub attestation [`35945988`](https://github.com/lucassu2012/ANEB_GPT/attestations/35945988), Rekor index `2193995642`; the public bundle passed offline `gh attestation verify` with repository, workflow, source commit/ref, GitHub-hosted runner, and subject digest pinned.
- The external fixed-CA gateway TLS/netem namespace test was not executed because the leaf certificate/key secrets were not configured. Gateway control-plane, race, and Linux-build gates did pass.

The cloud and local Debug APK hashes/signers intentionally differ because clean GitHub runners use a separate ephemeral Debug keystore. Never substitute either Debug fingerprint for the Product Owner's release-key fingerprint.
The P40 validation used the controlled development/ADB path and therefore does not establish the non-ADB end-user installation journey.
