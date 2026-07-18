# ANEB security and credential handling

## Report a vulnerability

Do not open a public issue containing credentials, private keys, user data, device evidence, or an unpublished vulnerability. Contact the Product Owner through a private channel and include only the minimum reproduction material. Rotate any exposed credential before investigating its use.

## Credential boundary

- Repository source, issues, pull requests, chat messages, screenshots, logs, APKs, JSONL exports and `evidence/` must not contain live credentials.
- GitHub, cloud, model-provider and release-signing credentials belong in the provider's encrypted secret store or an approved local secret manager. They must not be passed as command-line arguments when an environment/file-descriptor mechanism exists.
- The Android release keystore is a Product Owner asset stored and backed up outside this repository. Debug signing material is not release identity.
- A credential pasted into chat or another durable collaboration record is treated as compromised: revoke first, then create a least-privilege replacement. Deleting the visible message is not sufficient because copies and logs may remain.

## Automated gate

`python scripts/scan_repository_secrets.py` scans Git-tracked text files for high-confidence provider tokens, cloud access keys and private-key headers. It reports only rule/file/line and never the matched value. Symlinks and missing tracked files fail closed; binary files are skipped because this repository already rejects tracked keystores/APKs through source policy.

The scanner is a guardrail, not a credential inventory. A clean result does not make a shared credential safe or prove that Git history never contained one. After any disclosure, revoke the credential and inspect provider audit logs.
