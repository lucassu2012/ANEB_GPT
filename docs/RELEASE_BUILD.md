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
- Device validation: the directly preceding 0.5.9 P40 cellular AI realtime Quick candidate, run `019f7377-9a61-7db5-a8c4-1ac57de1a486`, completed 3/3 turns with 99.8/A and `LOW/INCONCLUSIVE`; its downloaded strict-v2 JSONL passed Schema/identity/digest validation with SHA-256 `FE964695E19997796F5FEB84E05F50FB69F61F2C6299FA0C577263E5198F7EA9`. The exact 0.5.10 binary above adds fail-closed MediaStore cleanup and has passed automated lifecycle fault injection, but has not yet been installed on the shared P40 because Experience Lab has not explicitly released the device. This boundary is intentional; 0.5.9 evidence is not presented as 0.5.10 device evidence. Neither artifact is signed release evidence.

## Cloud Debug candidate

Pushes to `main` and `codex/**` run the full cloud gate before an installable candidate is exposed. The Android candidate job waits for Profile/result/packaging contracts, Go server, dedicated gateway and behavior-model jobs; it then runs Android tests/Lint/build, verifies the Debug/Release component boundary, and packages the exact APK with:

- `build-manifest.json`: package, version, SDK, signer, file size, SHA-256 and source workflow identity;
- `checksums.sha256`: APK, manifest and Chinese install instructions;
- `ANEB-安装说明.txt`: non-ADB file-manager installation and mismatch stop rules.

Non-PR builds also create a GitHub artifact provenance attestation. The downloadable workflow artifact is retained for 30 days. It is explicitly `debug_non_release`; it may be used for controlled P40 validation but is not a signed public Release.

Verified cloud example (2026-07-18):

- Workflow run: [`29633753923`](https://github.com/lucassu2012/ANEB_GPT/actions/runs/29633753923), source `2dada77485891e117e448f58fa95020584c9d342`.
- Artifact ID/name: `8426436270` / `aneb-probe-debug-2dada77485891e117e448f58fa95020584c9d342`; expires `2026-08-17T06:26:44Z`.
- Cloud APK: 58,053,434 bytes; SHA-256 `2C05E347E66CC2049292452745DD68B6EDF2CECE2CB8501D509C4B9A6653DED1`; package `com.aneb.probe.codex`; `versionCode=42`; `versionName=0.5.10-codex`; Android Debug signer SHA-256 `8909F1E107AE2C74D6BE8711AEB249E8E9A4D8F8D6D7B6A8D941A65BD55A7D6E`.
- Provenance: GitHub attestation [`35942948`](https://github.com/lucassu2012/ANEB_GPT/attestations/35942948), Rekor index `2193564202`; the public bundle passed offline `gh attestation verify` with repository, workflow, source commit/ref, GitHub-hosted runner, and subject digest pinned.
- The external fixed-CA gateway TLS/netem namespace test was not executed because the leaf certificate/key secrets were not configured. Gateway control-plane, race, and Linux-build gates did pass.

The cloud and local Debug APK hashes/signers intentionally differ because clean GitHub runners use a separate ephemeral Debug keystore. Never substitute either Debug fingerprint for the Product Owner's release-key fingerprint.
