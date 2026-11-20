# Cold-Backup and Disk Governance Policy

Last updated: 2026-11-21 (CST)

This document defines the read-only integrity verification, off-disk cold-backup, Git offline bundle, and `E:` disk capacity governance rules for ANEB.

---

## 1. Hard Redlines (Non-Negotiable Safety Invariants)

1. **Zero Plaintext Secret Exposure**:
   - **NEVER** read, open, deserialize (`Get-Content`, `Import-Clixml`, `certutil -p`), print, log, or commit the contents of files inside `E:\ANEB-Secure-Backup\release-signing\`.
   - All automated and manual integrity checks on `E:\ANEB-Secure-Backup` MUST operate strictly on file metadata (path, byte length, timestamp) and SHA-256 hashes (`Get-FileHash -Algorithm SHA256`).
2. **NTFS Junction Preservation**:
   - **NEVER** run `Remove-Item -Recurse`, `rmdir /s`, `rm -rf`, or "delete and recreate" against any NTFS junction path — PowerShell 5.1 traverses junctions and destroys real target data on `F:` or `E:\G Project\ANEB`.
   - Always check `(Get-Item <path> -Force).LinkType` before any cleanup or move; if `LinkType -eq "Junction"`, operate only on explicit non-junction child files if authorized, never the junction itself.
3. **Recycle Bin Preservation**:
   - **NEVER** empty or purge the `E:` Recycle Bin without explicit Product Owner (PO) confirmation.
4. **Immutable Evidence & Shared Build Assets Preservation**:
   - **NEVER** modify, overwrite, or delete consumed immutable experiment card directories under `E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\`.
   - **NEVER** delete `E:\G Project\ANEB\References\BuildDependencies\` (offline Maven / Robolectric 15 jars) or active registered worktrees under `E:\G Project\ANEB\DevSpace\`.
5. **Disk Capacity Floor (`E: > 40 GiB`)**:
   - Maintain `E:` free space strictly **`> 40 GiB`**. Trigger governance warning below `42 GiB` and halt new heavy media capture campaigns at `<= 40 GiB` until PO-approved cold offload restores headroom.

---

## 2. Verified NTFS Junction Inventory (Read-Only Baseline)

| Junction Path | LinkType | Resolved Target | Notes |
|---|---|---|---|
| `%USERPROFILE%\.codex` | `Junction` | `F:\CodexHome` | Critical Codex state on `F:` |
| `%LOCALAPPDATA%\ANEB` | `Junction` | `F:\LocalAppData\ANEB` | Critical ANEB local app data on `F:` |
| `E:\C Project\ANEB` | `Junction` | `E:\G Project\ANEB\Archives\ClaudeWorkspace\ANEB` | Frozen donor repo (`df787280`) |
| `%USERPROFILE%\Downloads\ANEB_v1-v4_to_v2_20261109` | `Junction` | `E:\G Project\ANEB\Archives\ConversationExports\ANEB_v1-v4_to_v2_20261109` | Conversation export archive |
| `D:\Desktop\ANEB开发设计` | `Junction` | `E:\G Project\ANEB\References\DesignPackages\DesktopArchive` | Design packages archive |
| `%USERPROFILE%\.android\avd\aneb.avd` | `Junction` | `E:\G Project\ANEB\Archives\AndroidAVD\aneb.avd` | Android AVD image |
| `%USERPROFILE%\.android\avd\aneb-api35.avd` | `Junction` | `E:\G Project\ANEB\Archives\AndroidAVD\aneb-api35.avd` | Android API 35 AVD image |
| `%USERPROFILE%\.android\avd\aneb-api36.avd` | `Junction` | `E:\G Project\ANEB\Archives\AndroidAVD\aneb-api36.avd` | Android API 36 AVD image |
| `%USERPROFILE%\.android\avd\aneb-apiprobe.avd` | `Junction` | `E:\G Project\ANEB\Archives\AndroidAVD\aneb-apiprobe.avd` | Android probe AVD image |
| `%USERPROFILE%\.android\avd\aneb-continuity.avd` | `Junction` | `E:\G Project\ANEB\Archives\AndroidAVD\aneb-continuity.avd` | Android continuity AVD image |

Verify all junctions remain intact at any time using:

```powershell
@(
  "$env:USERPROFILE\.codex",
  "$env:LOCALAPPDATA\ANEB",
  "E:\C Project\ANEB",
  "$env:USERPROFILE\Downloads\ANEB_v1-v4_to_v2_20261109",
  "D:\Desktop\ANEB开发设计",
  "$env:USERPROFILE\.android\avd\aneb.avd",
  "$env:USERPROFILE\.android\avd\aneb-api35.avd",
  "$env:USERPROFILE\.android\avd\aneb-api36.avd",
  "$env:USERPROFILE\.android\avd\aneb-apiprobe.avd",
  "$env:USERPROFILE\.android\avd\aneb-continuity.avd"
) | ForEach-Object { Get-Item $_ -Force | Select-Object FullName, LinkType, Target }
```

---

## 3. `E:\ANEB-Secure-Backup` Layout, SHA-256 Baseline & Cold-Backup SOP

### 3.1 Verified Metadata & Digest Baseline (4 Files, 8,350 Bytes Total)

| Relative Path (`E:\ANEB-Secure-Backup\`) | Size (B) | Last Modified | SHA-256 Digest |
|---|---|---|---|
| `release-signing\aneb-production-release-cert.pem` | `1,954` | `2026-08-27 23:07:01` | `bb6aac4162833c81202dba0440460d9fafa742e0626f99a3369cbb7b0b9a4d64` |
| `release-signing\aneb-production-release-public-metadata.json` | `710` | `2026-08-27 23:09:05` | `4172320ce70c90fb5a5eb0a5bda8266d53a956f74bd26c1bc9c283e27b7913de` |
| `release-signing\aneb-production-release.credentials.clixml` | `1,276` | `2026-08-27 23:06:59` | `64f64b863afcc04cfd1524d60d70f28dc71bdd5b9b8a07285614b33f68d4e8a3` |
| `release-signing\aneb-production-release.p12` | `4,410` | `2026-08-27 23:07:01` | `d62dbc799039471c486756b83634e3c41029cd6544a852a5ee691c4aa986794a` |

- Release signing certificate fingerprint pinned in Android build configuration: `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`.

### 3.2 Hash-Only Local Verification Procedure (Zero Content Read)

```powershell
Get-ChildItem "E:\ANEB-Secure-Backup" -Recurse -Force -File |
  Select-Object FullName, Length, LastWriteTime,
    @{N='SHA256';E={(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}}
```

Compare every `Length` and `SHA256` output against §3.1. Any unexpected file, missing file, size change, or hash mismatch requires immediate PO escalation before signing any release APK.

### 3.3 Manual Off-Disk Encrypted Cold-Backup Verification SOP

Because `E:\ANEB-Secure-Backup` resides solely on local drive `E:`, it is a single-drive failure risk until mirrored off-disk:

1. **DPAPI Portability Caveat**: `aneb-production-release.credentials.clixml` is encrypted using Windows DPAPI bound to the current Windows user and machine credentials. Copying `.clixml` alone to a different PC or fresh Windows reinstall will **not** decrypt the signing passphrase. The PO MUST separately verify that the recovery passphrase for `aneb-production-release.p12` is stored in an offline encrypted vault (e.g., BitLocker/VeraCrypt container or hardware password manager) alongside the `.p12` and `.pem` files.
2. **Prepare Encrypted Target Medium**: Mount a hardware-encrypted USB drive, BitLocker volume, or VeraCrypt container (`<COLD_MOUNT>`). Never copy signing material onto unencrypted removable media or cloud sync folders.
3. **Copy & Generate Detached Manifest**:
   ```powershell
   Copy-Item "E:\ANEB-Secure-Backup" -Destination "<COLD_MOUNT>\ANEB-Secure-Backup-YYYYMMDD" -Recurse
   Get-ChildItem "<COLD_MOUNT>\ANEB-Secure-Backup-YYYYMMDD" -Recurse -Force -File |
     ForEach-Object {
       $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
       "$h *$($_.FullName)"
     } | Set-Content -Encoding ascii "<COLD_MOUNT>\ANEB-Secure-Backup-YYYYMMDD.sha256"
   ```
4. **Verify 1:1 Hash Parity Before Eject**: Re-run `Get-FileHash -Algorithm SHA256` on `<COLD_MOUNT>\ANEB-Secure-Backup-YYYYMMDD` and verify every digest matches §3.1 identically before dismounting/ejecting the cold medium.
5. **Log Receipt Without Secrets**: Record only the verification date, operator, target medium identifier, and hash-match pass/fail status.

---

## 4. Git Offline Bundle Inventory & Refresh SOP

### 4.1 Verified Bundle Inventory

| Archive Path | Size (B) | Role & Verification Status |
|---|---|---|
| `Archives\CodexGitBackups\aneb-probe-codex-v0.2.0-full-20261120-wave2.bundle` | `9,860,177` | Latest verified full Codex bundle prior to M2/M3 (`befa87b8`, 302 refs, SHA-256 `eebcd0d23038f78a243bfe73e77e6448f9ca8881bd8c89cee18a7980f1d510c3`, `git bundle verify` PASS) |
| `Archives\CodexGitBackups\aneb-probe-codex-v0.2.0-full-20261120-*.bundle` & `20261119-*.bundle` & `20261115.bundle` | 9.67–9.82 MB each | Earlier milestone snapshots (`p0p3`, `084`, `stage4`, `rb-pass`, `voice-ui`, `20261119`, `20261115`) plus `aneb-android-echo-v0.1.0-full-20261115.bundle` (`536,592 B`) and 2 stash patches (`stash-0`, `stash-1`) |
| `Archives\ClaudeWorkspace\ANEB_CC_backup_20261115.bundle` | `25,967,807` | Frozen Claude donor full bundle (SHA-256 `fa8463cc1e6d0001890dbed848d4fac7ccbdbaf0f07630b56011c4ad348ac6c6`) |

> **Freshness Note**: `codex/business-video-baseline-1024` has advanced past `befa87b8` (`wave2.bundle`) through M2 (`bb134903`), M3 (`6ce3e883`), and M4. Refresh and verify a new full `.bundle` at M4/M5 closeout (and mirror it onto off-disk cold storage alongside `E:\ANEB-Secure-Backup`).

### 4.2 Refresh & Verification Commands

```powershell
$repo = "E:\G Project\ANEB\DevSpace\aneb-business-video-baseline-1024"
$bundle = "E:\G Project\ANEB\Archives\CodexGitBackups\aneb-probe-codex-v0.2.0-full-YYYYMMDD.bundle"
git -C $repo bundle create $bundle --all
git -C $repo bundle verify $bundle
Get-FileHash -LiteralPath $bundle -Algorithm SHA256
```

---

## 5. `E:` Disk Capacity Governance & Lossless Read-Only Media Archival

### 5.1 Current Capacity & Footprint Breakdown (`2026-11-21` Baseline)

- **`E:` Logical Disk**: `212.00 GiB` total (`227,634,311,168 B`), **`44.60 GiB` free** (`47,888,740,352 B`), `167.40 GiB` used.
- **Headroom Alert**: Only **`4.60 GiB`** remains above the `40 GiB` floor after recent Stage 1 video/APK captures (`51.91 GiB` on `2026-11-19` -> `44.60 GiB` on `2026-11-21`).
- **`Business-Profile-Baseline-20261024` Footprint**: `7.943 GiB` across `98` top-level directories and `14,935` files:
  - `.apk` (`126` files): **`3,925.98 MiB`** (per-card `probe-debug.apk` / `main-before.apk` copies, ~`61.4 MiB` each)
  - `.png` (`1,483` files): **`1,719.59 MiB`** (extracted video frames)
  - `.mp4` (`34` files): **`1,719.07 MiB`** (raw screen / `MediaProjection` recordings)
  - `.exe` (`51` files): `387.83 MiB`
  - `.json` (`9,246` files), `.zip` (`25` files), `.txt` (`365` files), `.wav` (`11` files): ~`243.83 MiB` combined
  - Note: `.apk` + `.png` + `.mp4` account for **92.7% (`7.365 GiB`)** of the entire research archive, whereas JSON receipts, scripts, and reports take under `250 MiB`.

### 5.2 Lossless Read-Only Archival Policy (PO-Approved Offload Only)

Because consumed experiment cards are strictly immutable (`PROJECT.md`), **never delete or mutate files inside an active or unarchived card directory**. When `E:` approaches `42 GiB` (warning) or `40 GiB` (hard floor), reclaim space only through the following lossless, read-only two-stage workflow:

1. **Select Eligible Historical Batches**: Target only closed, QA-verified historical batches (e.g., superseded pre-RC runs or older raw frame sequences) approved by the PO for cold offload. Keep active/baseline `JSON` manifests, Kotlin verifier receipts, `INFLECTION_REPORT.md` / `DOUBAO_RB_REPORT.md`, and `.svg` curves locally accessible.
2. **Generate Pre-Archive SHA-256 Manifest**:
   ```powershell
   $src = "E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\<card-dir>"
   Get-ChildItem -LiteralPath $src -Recurse -File | Sort-Object FullName | ForEach-Object {
     $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
     "$h *$($_.FullName)"
   } | Set-Content -Encoding utf8 "<staging>\<card-dir>.sha256"
   ```
3. **Create Lossless Compressed Archive (`bsdtar 3.7.7 --zstd`)**:
   Use Windows native `C:\Windows\system32\tar.exe` (`bsdtar 3.7.7`, `libzstd/1.5.4`) to pack the read-only card directory without modifying any source timestamps or files:
   ```powershell
   C:\Windows\system32\tar.exe --zstd -cf "<COLD_MOUNT>\aneb-cards\<card-dir>.tar.zst" `
     -C "E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024" "<card-dir>"
   ```
4. **Verify Archive Round-Trip & Cold Copy Before Any Reclamation**:
   - Compute SHA-256 of `<card-dir>.tar.zst` on `<COLD_MOUNT>`.
   - Extract to a temporary verification directory (`tar.exe --zstd -xf ... -C <temp>`) and verify 100% file count and SHA-256 match against `<card-dir>.sha256`, then delete `<temp>`.
   - Only after PO sign-off and verified off-disk cold storage may raw `.mp4`/`.png`/duplicate `.apk` bulk files from archived batches be pruned locally (retaining the card's `JSON` manifest, SHA-256 receipt, and archive pointer).
5. **Hygiene Guardrails**:
   - Never prune `References\BuildDependencies\` or registered `DevSpace\` worktrees without an explicit worktree audit.
   - Re-measure `E:` free space (`Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='E:'"`) after any archive operation to confirm `FreeSpace > 40 GiB`.
