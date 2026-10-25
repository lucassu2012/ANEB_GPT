# Single-phone forwarding condition — integration WIP, NOT LIVE READY

This is the first bounded part of the already approved business-Profile baseline,
not a replacement App, general traffic framework, KPI calculator or new protocol.
The existing Linux node netem acts at the node; it cannot shape arbitrary P40 App
traffic. This candidate targets one phone's host-address set at one verified
Windows forwarding interface. It does not configure sharing, routes, firewall,
VPN, phone settings or security policy.

Implemented and locally exercised so far:

- finite condition validation and generated destination/interface filter;
- serial IP-byte pacing, without an initial burst or catch-up reservations;
- planned expiry as a normal terminal event, but parent cancellation, clock faults
  and injection/cleanup failures as invalid terminal events;
- overlapping injection owners are rejected and invalidate the condition;
- the pinned WinDivert2.2.2 address ABI (80 bytes, union offset16).
- independent bounded receive queue, pacing, cancellation restoration, overflow
  invalidation, copy/ownership checks and bounded cleanup with unjoined failures;
- Windows amd64 native binding, pinned/read-locked dependency files, generated
  FORWARD filter, explicit SNIFF flags, queue setting readback and owned close;
- a plan CLI that works without opening a driver or creating runtime output;
- a Windows owned-process Job supervisor, actual suspended-child/normal-exit/
  timeout/close tests, and a create-once supervised inspection command.
- explicit `limit`/private worker routing through the same parent, Job and
  existing core; fixed request mode cannot be inferred by an `inspect` command.

The public limits (at most16 host addresses, nonzero interface, TTL up to120s,
0<rate<=1,000,000,000 IP bytes/s, packets up to65575 bytes) are implementation
bounds, **not** measured network quality, business thresholds or a frozen B value.
Addresses must describe one phone, not16 phones. The caller still has to prove
the actual ICS/NAT/interface mapping and completeness of IPv4/IPv6 routing.

## Not implemented / not accepted yet

There is **no released or qualified phone limiter**. The CLI now accepts `plan`,
the `inspect` qualification candidate and an explicit `limit` candidate. Neither
execution command is a live release: it needs its own fixed configuration,
approved system preflight, verified topology and calibration before use. Both
require an already approved elevated console; there is no auto-elevation or
security-policy change. No test loads the valid vendor library or driver. Packet
tests use synthetic links/clocks; process tests run only the module's own test
binary. No APK, hotspot or network setting was operated by these tests. The
standard local gate and Windows CI include the module, but an earlier passed
gate does not cover a later dirty candidate or prove real network behavior.

Real qualification must use SNIFF|RECV_ONLY (0x5)
and never Send copies. A blocking mode uses the bounded independent receiver and
owned queue, TTL established before Open plus the supervisor, Shutdown(RECV)
then bounded driver/user queue drain while Send remains open, and only own Close.
Overflow, truncation, send failure or forced
termination retain failures and unknown driver-queue losses. Close is not an
unload/zero-loss promise. No generic sc stop/delete or security-policy bypass.

Preserve the complete original80-byte Address with each copied packet. The
receiver must not alias a reused native buffer into the pending queue. Send's
success is host-stack injection only, never phone receipt. Scheduling offsets
are local host diagnostics, not App wait, RTT, phone elapsed time or MP4 PTS.

Before real use: fixed candidate review and quality
gates, exact binary/dependency/configuration pins, actual system approval, fresh
network/device preflight, reversible forwarding qualification and phone-side
calibration. Existing failed App attempts and V2 NA stay unchanged. The new
helper grants no research sample, repetition, live driver or network quota.

## Supervised inspection candidate, not a condition qualification

`phone-condition plan -config <fixed-request.json>` is non-executing.
`phone-condition inspect -config <fixed-request.json>` is the non-blocking
candidate; **do not invoke it before the fixed-candidate gates, scoped system
approval and fresh network/device preflight**. The caller must first establish
actual driver/service provenance and absence of an unknown conflicting filter;
source DLL/SYS hashes do not identify an already loaded kernel image.

`phone-condition limit -config <fixed-request.json>` is the blocking-mode
candidate and requires a request whose mode is exactly `limit`. `inspect` only
accepts `inspect`. The private worker entry likewise pins its own mode and the
original request hash. Successful core receipts remain `NO_MATCHING_PACKETS`,
`NO_PACED_PACKETS` or `INJECTED_NOT_CALIBRATED`; none proves phone delivery or
calibrated quality. Missing/failed/unjoined results stay failures. Existing
synthetic packet tests and the real-core worker test do not load a driver.

The parent read-locks its own executable, creates a new output directory, keeps
the exact request bytes and hashes, then creates a suspended copy of itself.
Only after assignment to its anonymous KILL_ON_JOB_CLOSE Job and arming the
original deadline is the private inspection worker resumed. The parent deadline
is request TTL plus a fixed 5-second startup/cleanup margin, capped at 120s;
therefore inspection requests may specify 1..115s, not the plan API's full 120s.
This is a host control lifetime, not a changed App observation window.

Output is create-once: `request.json`, `parent-start.json`, `worker-start.json`,
`worker.json`, and `supervisor.json`. Partial files, missing final worker output,
nonzero exits, forced terminations and cleanup failures remain failures. A normal
TTL with no packets is `NO_MATCHING_PACKETS`, not proof of forwarding or absence
of losses. Copies seen successfully are still `OBSERVED_NOT_QUALIFIED`.
These receive totals include shutdown drain; they do not delimit an App window.
`PhoneReceivedBytes` and `DriverDroppedPackets` remain unknown/null.

The private `_worker-inspect` entry checks elevation, request identity and Job
membership, but membership alone is not identity of the parent's Job. It prevents
accidental uncontained use, not a privileged local caller intentionally invoking
internal entries. It is not a public authorization or sandbox boundary.

The supervisor kills only its returned process handle or owned Job, never a PID
or service name. Kill-on-close protection begins **after successful assignment**:
a parent crash between CreateProcess and Assign may leave a suspended, not yet
executing child. The timer signals the parent; a frozen parent or stuck native OS
call has no hard wall-clock guarantee. Exit/tree/handle-close errors are retained.
Closing the worker/Job is not a claim that the driver is unloaded or queues had
zero loss; actual loaded-service state must be reviewed separately, never removed
by a generic stop/delete command.

Normal end-of-window and genuine errors stay separate. An unsent packet that
cannot fit waits until the original cutoff, then is restored unchanged only in
cleanup. Cleanup injections are not paced experiment traffic. An admitted send
with an uncertain result is never retried or normalized to success. Parent
cancellation and a cleanup error still invalidate a nominally normal expiry.

`OwnedReceivedPackets` counts complete non-Sniffed frame headers. On a conflicting
capture mode it is not proof of physical blocking. `UnconfirmedUserPackets` is
the unresolved count among those copied into user memory, not total network/IP
loss; packets lost before Receive cannot be counted by this API. A send is
considered admitted once its native-call task has been dispatched, even before
its actual method entry. Only a pre-dispatch context rejection guarantees it
will never execute and permits cleanup restoration.

## Local core tests

```
cd tools/phone-forward-condition
go test -count=1 ./...
go vet ./...
```

Tests use only synthetic packets and the local clock/injection system boundary.
No test loads WinDivert or captures traffic. No production signer or third-party
dependency is added to this Go module; vendor binaries remain outside Git.
