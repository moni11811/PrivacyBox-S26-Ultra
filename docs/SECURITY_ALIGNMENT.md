# Android and release security alignment

Review date: 2026-09-01

Scope: the current Privacy Box Android source, generated release manifest, build-integrity controls, verified private v1.0.5 candidate, and public release bundle. This is defensive source review and verification, not a penetration-test certification, legal opinion, or guarantee of Samsung firmware behavior.

## Current Android controls

| Security area | Current control | Evidence status |
|---|---|---|
| Samsung privacy capability and state | All three Samsung methods must resolve. Activation includes both enable and position calls. ACTIVE is published only after success; invocation, timeout, and cleanup failures remove the overlay and remain ERROR or BLOCKED. | Implemented and covered by JVM and instrumented failure tests. Physical confidentiality still depends on undocumented Samsung firmware. |
| Exported launcher continuation | MainActivity discards incoming continuation data. Permission continuation uses MODE_PRIVATE state bound to boot count and elapsed time, expires, and is cleared before start. | Implemented with cold-external, expiry, reboot, one-shot, and storage-failure tests. |
| Components and PendingIntents | The overlay service is non-exported. The exported tile requires BIND_QUICK_SETTINGS_TILE. Notification and tile PendingIntents are explicit and immutable. | Confirmed in source tests and the merged release manifest. |
| Locked touch-through | Non-touchable overlay windows receive FLAG_NOT_TOUCHABLE and use Android's runtime maximum obscuring opacity rather than alpha 1.0. | Implemented and unit tested. Exact cross-UID behavior remains a physical-device property. |
| Backup and transfer | Application backup is disabled. Both privacy_overlay and overlay_start_authorization preferences are also excluded from cloud, device-transfer, and legacy backup rules. | Confirmed in source and merged release manifest. |
| Hidden Samsung API | Reflection is resolved fail-closed and never treated as proof that unsupported firmware is safe. | Compatibility limitation remains; no certification claim is made. |

## Release workflow finding and remediation

The post-Daybreak audit's medium finding was valid: workflow_dispatch source_tag was expanded directly into Bash, including the secret-bearing signing step. Shell parsing happened before the version regex, and the mutable checkout could diverge from the tag archive.

The private v1.0.5 candidate workflow closed that path before publication:

- the raw input appears once, as the SOURCE_TAG environment binding for the first validator, and never inside a run scalar;
- the workflow must itself be dispatched from the same exact refs/tags reference, in a private repository, so environment tag rules apply to the source being built;
- the validator requires a direct annotated tag object targeting a commit and records tag object, commit, tree, and deterministic source-archive digest;
- validated step outputs are the only later tag identity;
- tracked and untracked source state is rechecked immediately before and after the clean build;
- export-ignore and export-subst attributes are rejected so Corresponding Source cannot differ from the buildable tree;
- the unsigned APK handoff is checksum-bound and explicitly allowlisted;
- signing runs in a fresh environment-protected job that executes no repository code, independently checks out and recreates Corresponding Source, and rejects extra files, symlinks, tag/tree drift, or digest drift before secrets are exposed;
- Android Build Tools/apksigner 36.0.0 is selected by exact path; the approved certificate digest comparison remains mandatory;
- both the one-day unsigned handoff and seven-day candidate artifact require a private repository and use explicit file allowlists; no release, package, or Play publication occurs.

The deterministic verifier scans workflow and local composite-action run scalars, rejects direct or nested input expressions and dynamic shell evaluation, and constrains the sole raw input reference to the validator's environment binding. Its regression fixtures cover block and folded scalars, multiline/nested expressions, bracket contexts, aliases, flow mappings, escaped run keys, and eval.

## Build integrity

- Gradle wrapper and distribution are pinned to 9.1.0 with official SHA-256 values.
- Android Build Tools are pinned to 36.0.0.
- Dependency locking is strict and verification metadata contains SHA-256 hashes.
- THIRD_PARTY_NOTICES.md must equal the 35-coordinate release runtime lock graph.
- CI and candidate actions use full commit SHA pins, read-only repository permissions, and checkout without persisted credentials.
- Release readiness passed only after owner, artwork, application-ID, and approved certificate inputs were verified.

## Deliberate non-findings and limitations

- Resize ACTION_CANCEL rearms privacy and is not treated as a security or release blocker, per the user decision.
- The notification-permission prompt on visible exported launcher creation is normal launcher behavior, not an external continuation bypass.
- No online dependency-vulnerability review was completed by these local integrity checks.
- Repository source cannot prove optical privacy on every Samsung firmware build.

## Published release status and residual limitations

The signed v1.0.5 release is public under protected tag `v1.0.5`. Its APK, checksums, source identity, exact matching AGPL Corresponding Source, runtime notices, and Apache license were independently read back from the public GitHub surface. The release certificate matches the approved digest in `docs/RELEASE.md`.

Residual limitations remain explicit:

1. the private release keystore and macOS Keychain passwords must remain protected and recoverable;
2. automated CLA/DCO enforcement is not configured, so owner confirmation is required before merging contributions;
3. no independent legal opinion is claimed for the AGPL, CLA, notices, or artwork declaration;
4. dependency integrity is pinned and verified, but future releases still require a current advisory review;
5. exact optical privacy remains a Samsung firmware and physical-device property, not a source-code certification;
6. the hardened candidate workflow intentionally requires private staging and cannot be rerun while this repository is public without a separately reviewed policy change.

AGPL Corresponding Source obligations apply to this program and its distribution. They do not claim control over independently written clean-room software merely because it calls a Samsung API.
