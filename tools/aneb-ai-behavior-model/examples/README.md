# Calibration input templates

These files describe structure only. They are not an authorized dataset and cannot produce a calibrated model as committed.

- `calibration_metadata.pending.json` deliberately has `authorization.status=pending`. Create a local copy and set it to `authorized` only when a real approval record exists.
- `token_observation.example.jsonl` contains synthetic derived statistics. Never replace it with prompts, account identifiers, API keys, response content, or payload bodies.
- Generate `subject_group_id` with HMAC-SHA256 and a dataset-specific secret that is never written to the dataset or repository. Plain SHA-256 of an account identifier is not acceptable.

The local `datasets/` directory is ignored by Git.
