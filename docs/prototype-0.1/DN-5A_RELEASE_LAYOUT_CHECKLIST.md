# DN-5A release-layout checklist

Status: contract-neutral checklist only. This document does not freeze DN-1 or DN-4 semantics and does not admit a server, APK, or release candidate.

## 1. Allowed machine-contract set

The package may contain these four existing machine-contract files, at these exact relative paths:

- `contracts/prototype-0.1/capabilities.schema.json`
- `contracts/prototype-0.1/profile-manifest.json`
- `contracts/prototype-0.1/run-record.schema.json`
- `contracts/prototype-0.1/score-policy.json`

`contracts/prototype-0.1/README.md` is explanatory documentation, not a fifth machine contract. `evidence-schema.json` is not part of this layout; its presence is a failure, not an invitation to infer a replacement schema.

## 2. Fresh-directory layout gate

Run the check against a newly created package directory. Every admitted path must be a regular, non-reparse file below that directory. Paths must be canonical, relative, and free of absolute roots, drive prefixes, UNC prefixes, `..`, empty components, alternate-stream syntax, case-fold collisions, and Unicode-normalized duplicates. The checker must never create a missing package contract or parent directory during verification.

The final candidate must have a deterministic file inventory. The four paths above are the only machine-contract paths admitted by DN-5A; any additional contract-like file is an extra. Other release files remain governed by the separately approved package layout and are not defined here.

## 3. VERSION and hash binding

`VERSION.json` and the package hash record must be regular, non-reparse files inside the fresh package. The hash record must include `VERSION.json` and each of the four exact contract paths using their actual relative paths, sizes, and SHA-256 values. The verifier recomputes all values from bytes; it does not trust timestamps, file names supplied by a caller, or a status field that says `ready`.

DN-5A checks binding and closure only. Product version values, clock policy, null-reason policy, and detailed contract semantics remain Product Owner decisions and must not be invented by this checklist.

## 4. Required negative cases

Each case must be executed against its own fresh copy and must leave the original input unchanged:

| Mutation | Required result |
| --- | --- |
| Remove any one of the four files | Non-zero `DN5A_MISSING_CONTRACT` |
| Add `evidence-schema.json` or another undeclared machine contract | Non-zero `DN5A_EXTRA_CONTRACT` |
| Add a duplicate path differing only by case or Unicode normalization | Non-zero `DN5A_DUPLICATE_CONTRACT_PATH` |
| Change one contract byte or its `VERSION.json`/hash record byte | Non-zero `DN5A_HASH_MISMATCH` |
| Change a hash-record path, size, or SHA-256 | Non-zero `DN5A_HASH_BINDING_MISMATCH` |
| Use an absolute, traversal, UNC, drive, ADS, symlink, junction, reparse, or non-regular entry | Non-zero `DN5A_UNSAFE_PATH` |
| Replace the package root or hash record during verification | Non-zero fail-closed result; no accepted package |

The exact numeric exit mapping remains a release-contract decision; the symbolic failure classes above are checklist identifiers, not frozen product API.

## 5. Safe-stop evidence

Record the fresh root, exact relative inventory, bytes, SHA-256 values, verifier version/hash, command/argv, start/end UTC, numeric exit code, and whether the original input changed. A missing server or APK is a blocking artifact condition; it must not be replaced with a synthetic file or counted as a release pass.

DN-5A PASS means only that this layout/integrity checklist passed. It is not G0, G3, G4, compatibility, product, Android, or network readiness.
