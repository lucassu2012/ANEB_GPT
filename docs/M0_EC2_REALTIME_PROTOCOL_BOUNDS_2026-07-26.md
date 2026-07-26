# M0-EC2 Realtime Quick protocol-bound correction

## Decision

- ［KNOWN｜HIGH］The first formal positive collection is not READY. It failed
  closed before publication and remains diagnostic evidence only.
- ［KNOWN｜HIGH］The failure was caused by two independent verifier defects:
  Windows CRLF was rejected as a single JSON output record, and the audit
  contract incorrectly required server-emitted downlink frames to equal client
  unique frames.
- ［KNOWN｜HIGH］The server journal must retain its actual emitted count. The
  verifier must not rewrite 678 emitted frames as 676 client frames.

## Bounded protocol contract

The Quick runtime contains two uninterrupted turns and one interrupted turn.
For each uninterrupted turn, the emitted upper bound is the planned downlink
frame count. For an interrupted turn, the upper bound is:

```text
min(
  planned_downlink_frames,
  downlink_frames_before_stop + ceil(expected_stop_within_ms / frame_ms)
)
```

For the frozen `ai_realtime_voice_quick@1.1.1` runtime:

- ［COMPUTED｜HIGH］Client effective/unique minimum: 676 frames / 648,960 bytes.
- ［COMPUTED｜HIGH］Server emitted maximum within the 300 ms stop window:
  691 frames / 663,360 bytes.
- ［KNOWN｜HIGH］The retained server summary contains 678 emitted frames, which
  is inside the inclusive `[676, 691]` contract.

The execution-evidence contract is now:

- schema: `aneb-realtime-protocol-bounded-contract`
- contract: `aneb-realtime-quick-protocol-bounds@1.1.0`
- canonical SHA-256:
  `4d1f61844121970625c281fd278da60e207fa14107556bbae1161a6d0d85acc6`
- catalog: `aneb-spec-catalog@1.7.0`
- audit report schema version: `1.1.0`

All fields except emitted downlink frames remain exact. Emitted downlink frames
must be an integer inside the derived inclusive bound; 675 and 692 are rejected.

## Original-scenario replay

- ［KNOWN｜HIGH］The frozen positive run
  `019f9de9-ed5c-7fb2-86c3-3f7b0346b104` was replayed without ADB, SSH, or any
  server mutation.
- ［KNOWN｜HIGH］The client verifier retained 676 expected and 676 unique frames,
  one session, three turns, one interrupted turn, and 79 loaded RTT attempts.
- ［KNOWN｜HIGH］The audit verifier retained 678 server-emitted frames and 79
  echo entries and returned `status=pass, reason_code=ok`.
- ［KNOWN｜HIGH］The cross-bundle verifier independently revalidated the frozen
  Room database, raw journal, client result, audit report, serverinfo, protocol
  digest, and returned `status=pass, reason_code=ok`.

## Regression evidence

- ［KNOWN｜HIGH］Realtime focused contract tests: 46/46 passed.
- ［KNOWN｜HIGH］Realtime direct-consumer tests: 59/59 passed.
- ［KNOWN｜HIGH］Repository script tests: 732 passed, 16 skipped by design.
- ［KNOWN｜HIGH］Behavior-model tests: 43/43 passed.
- ［KNOWN｜HIGH］Android unit/lint/release-manifest/debug-assemble gate passed.
- ［KNOWN｜HIGH］Release boundary, secret scan, spec catalog, result schema,
  debug-candidate packaging, server Go tests, and gateway Go tests passed.

## Remaining release gates

1. Commit and push the CRLF and bounded-contract correction.
2. Require a green GitHub-hosted candidate workflow and verify its provenance.
3. Migrate the P40 debug install to that exact CI candidate while preserving
   the frozen Room state.
4. Run a new formal positive collection and publish READY only if every
   independent verifier and cleanup gate passes.
5. Run the separate negative `receipt_missing` zero-business collection and
   publish READY only under the negative contract.

Until both formal collections pass, M0-EC2 remains incomplete.
