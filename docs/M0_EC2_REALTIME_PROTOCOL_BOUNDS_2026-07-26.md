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

## Positive playout-quality verifier correction

- ［KNOWN｜HIGH］The next exact-CI positive run
  `019f9e53-4125-7bb7-8c9b-c039d24cd1f0` completed the frozen protocol with
  676 expected and 676 unique client frames. The server retained 678 emitted
  frames, inside the inclusive `[676, 691]` bound.
- ［KNOWN｜HIGH］Turn 1 measured 336 on-time frames, 14 conceal frames and 14
  stall frames out of 350. The previous client verifier incorrectly required
  every completed positive turn to have zero measured playout degradation.
- ［INFERRED｜HIGH］That condition confused evidence validity with measured
  quality. A completed, hash-bound protocol run may legitimately score below
  perfect quality; rejecting it would erase the network behavior ANEB is meant
  to measure.

The corrected verifier keeps the protocol identity gates exact and separately
checks playout-quality accounting:

- `expected_frames == unique_frames ==` the frozen runtime value;
- `success=true`, the interrupted flag and overlap semantics match the runtime;
- `max_missing_run_frames=0` when every expected sequence is present;
- `on_time_frames + conceal_frames == expected_frames`;
- `0 <= stall_frames <= conceal_frames`, and a non-zero stall contains at least
  the engine's frozen three-frame minimum.

Impossible accounting still fails closed with
`turn_quality_accounting_mismatch`. Real late/concealed/stalled frames remain in
the retained evidence and are independently recomputed into LIVE-B05/B06/B07,
group scores and the final score.

### Frozen-run replay after the correction

- ［KNOWN｜HIGH］Client DB verification passed with Room v19, 21 typed metrics,
  immutable DB/WAL/SHM hashes, exact Profile/runtime digests and 676/676 frames.
- ［KNOWN｜HIGH］Server audit passed with one realtime business entry, 78 echo
  entries, one protocol summary and 678 emitted frames.
- ［KNOWN｜HIGH］Cross-bundle verification re-read the frozen Room database,
  journal, serverinfo and both reports, then returned `cross_bound=true`.
- ［KNOWN｜HIGH］The seven direct Realtime Quick consumer modules passed 102/102
  tests, including valid degradation and impossible-accounting counterexamples.

This remains a diagnostic replay. A new clean commit, green CI provenance and
new formal positive and negative collections are still required before READY.

## Windows verifier-report persistence correction

- ［KNOWN｜HIGH］Formal positive run
  `019f9e80-8841-75b5-b4c7-98010e68b407` completed its business protocol and
  all finally cleanup gates. PhoneGuard returned the exact frozen Launcher
  state, the E-01 lock was released, and the remote fingerprints remained
  stable.
- ［KNOWN｜HIGH］Publication correctly failed closed before READY with
  `cross_report_invalid_noncanonical`.
- ［KNOWN｜HIGH］The cross verifier emitted one valid JSON record terminated by
  Windows CRLF (`0D 0A`). `_run_json_verifier` validated that record but then
  persisted the original platform newline bytes. The final collection consumer
  requires canonical JSON terminated by one LF (`0A`).

The collector now persists the already-validated report by canonical JSON
serialization instead of copying child-process stdout bytes. Parsing remains
strict: unterminated, multi-record, embedded-newline, leading-whitespace,
duplicate-key and non-JSON outputs are still rejected. A Windows CRLF regression
test proves the persisted report is exact canonical LF JSON.

The failed partial collection remains diagnostic only and is not repaired into
READY. A clean commit, green CI candidate and a new collection are required.

## Independent collection-consumer corrections

- ［KNOWN｜HIGH］Formal positive run
  `019f9ec4-df1c-73eb-891d-c0bc8535e1df` completed its business protocol and
  all phone/remote cleanup gates, but publication failed closed with
  `candidate_file_set_invalid`.
- ［KNOWN｜HIGH］The frozen candidate contained the exact five verified CI files,
  including `ANEB-安装说明.txt`. The collector and provenance verifier used that
  real packaged name, while the independent collection verifier and its
  synthetic fixture shared the same mojibake filename. Their mutual agreement
  hid the production incompatibility.
- ［KNOWN｜HIGH］After correcting that filename contract, a complete offline
  replay exposed a second independent-consumer drift: Android `dumpsys package`
  and empty `dumpsys activity services <package>` records use CRLF/header forms,
  while the verifier fixtures only exercised simplified LF and `(nothing)`
  forms.

The collection verifier now requires the real five-file Unicode set, accepts
only LF/CRLF for the exact installed version line, and recognizes only the
bounded Android empty-service representations already accepted by the
collector. It still rejects additional candidate files, non-empty service
dumps, invalid versions and non-canonical evidence JSON.

Seventeen focused verifier tests pass. A diagnostic reconstruction of the
frozen publication snapshot also passes the complete independent consumer,
including manifest, candidate, phone, remote, lock and recomputed cross-bound
evidence. Only the temporary parent-directory ACL binding was deliberately
substituted during that replay; the original failed collection remains
diagnostic and is not promoted to READY.

## Negative-proxy timeout-contract correction

- ［KNOWN｜HIGH］Two formal negative attempts stopped before App launch and
  published no READY. Both retained clean phone/remote cleanup evidence and the
  machine failure `negative_proxy_ready_invalid`.
- ［COMPUTED｜HIGH］The collector passed its 900-second whole-run timeout to the
  single-request negative proxy. The proxy contract permits only 1–300 seconds,
  so the child deterministically exited with `config_invalid` and return code 2.
- ［KNOWN｜HIGH］The parent discarded startup stderr and exit status, collapsing
  that precise child failure into the generic ready-line error.

The collector now passes the separately validated command timeout (120 seconds
for the formal plan) to the proxy and rejects any out-of-range value before
launch. A startup failure also persists bounded stdout/stderr plus their full
SHA-256 values, truncation flags and the child return code in the diagnostic
partial collection. These diagnostics do not relax the ready record, one-shot
proxy, reverse ownership, zero-business, cleanup, independent verification or
READY publication gates.

The real child-process feedback loop completed 50/50 loopback starts with the
corrected timeout. Ready latency was 0.358 seconds median and 0.604 seconds max.
The failed live attempts remain diagnostic only and will not be repaired into
READY.

## Huawei/Windows ADB reverse transport-label correction

- ［KNOWN｜HIGH］The first negative attempt after the proxy-timeout correction
  reached reverse creation but stopped before App launch. The scoped command
  `adb -s <exact-device-serial> reverse --list` returned the single mapping
  `UsbFfs tcp:18765 tcp:18765`.
- ［KNOWN｜HIGH］The collector incorrectly required the first column to equal the
  device serial. Android platform-tools exposes that column as an ADB transport
  label; the already-independent negative evidence verifier correctly treats
  it as `adb_transport_label_sha256`.
- ［KNOWN｜HIGH］The failed partial collection contains no run ID or READY and
  remains diagnostic only. Its one mapping was attributable because the
  preflight inventory was empty and the active inventory contained only the
  exact collector-created endpoint; cleanup removed only `tcp:18765`, then
  independently verified an empty final inventory and clean phone/server state.

The collector now preserves the strict ownership boundary without equating the
transport label with device identity:

1. preflight and final reverse inventories must be globally empty;
2. after `--no-rebind`, the inventory must contain exactly one expected
   device/host endpoint pair;
3. the bounded transport label is captured from that active mapping;
4. before removal, label and endpoint pair must exactly match the captured
   mapping, with no additional entries;
5. any label drift, endpoint drift or inventory pollution fails closed before
   deletion.

Device identity remains independently bound by the exact `adb -s` serial,
device policy, PhoneGuard preflight and installed-package identity. The raw
active/before-remove records remain independently checked by the existing
negative evidence verifier, including an exact transport-label match.

Regression evidence for this correction:

- ［KNOWN｜HIGH］Collector plus independent negative/cross-bundle verifier
  regression: 86/86 passed, including real `UsbFfs`, inventory pollution and
  transport-label drift cases.
- ［KNOWN｜HIGH］Repository main tests: 747 passed, 16 skipped by design;
  behavior-model tests: 43/43 passed.
- ［KNOWN｜HIGH］Android unit/lint/debug assemble, release boundary, secret scan,
  debug-candidate packaging, spec/result schemas, server Go and gateway Go all
  passed in the complete repository quality gate.

## Negative terminal-marker correction

- ［KNOWN｜HIGH］Commit `44049ed00f4d3367b171fbf75cf53be327747ea8`
  produced a formal positive READY, but its formal negative run timed out before
  publication with `realtime_marker_chain_invalid`; cleanup completed with zero
  failures and no READY was created.
- ［KNOWN｜HIGH］The real App log proved a durable zero-business rejection in
  this order: `START`, `PROFILE`, `RADIO`, `DB_WRITE ok=true`, `CONTRACT
  status=rejected reason=receipt_missing detail=...`, and `END
  status=contract_rejected`.
- ［KNOWN｜HIGH］The collector's synthetic fixture instead required
  `persisted=true` inside the CONTRACT marker. The Android implementation has
  never emitted that field, so the host consumer rejected the real chain until
  its 900-second timeout even though DB persistence had already succeeded.

The corrected host contract accepts the App's actual marker shape and keeps the
durability boundary explicit:

1. exactly one same-run `DB_WRITE ok=true` marker is required;
2. the negative CONTRACT field set must be exactly
   `run_id,status,reason,detail`;
3. status must be `rejected`, reason must be `receipt_missing`, and detail must
   be non-empty;
4. no result marker may exist and END must be `contract_rejected`;
5. the independent Room verifier must still prove one INVALID, null-score,
   zero-business retained result before READY publication.

The timed-out negative collection remains diagnostic only. The existing
positive READY is valid for commit `44049ed`, but a new clean commit, green CI
candidate, and same-candidate positive/negative pair are still required for the
final M0-EC2 gate.
