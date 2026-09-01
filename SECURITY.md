# Security policy

## Supported version

Until a public release is made, only the latest revision on the default branch is supported.

## Reporting a vulnerability

Do not open a public issue for a vulnerability, credential exposure, signing-key leak, or private device data. Use GitHub's private vulnerability reporting feature after it is enabled for the public repository.

Include the affected revision, device and firmware, prerequisites, reproduction steps, security impact, and any proof that avoids exposing unrelated personal data.

## Security properties

- The overlay service must remain non-exported.
- The Quick Settings tile must remain protected by Android's `BIND_QUICK_SETTINGS_TILE` permission.
- Pending intents must be explicit and immutable.
- Locked overlay surfaces must be non-touchable and keep `WindowManager.LayoutParams.alpha` at or below Android's runtime maximum obscuring opacity so input reaches a different-UID app underneath.
- Privacy-active status requires successful Samsung capability resolution, activation, and positioning; every Samsung invocation failure must remove the overlay and remain visibly failed.
- No network access, analytics, account data, or remote control is expected.
- Secrets, signing material, machine-local paths, built packages, logs, and private QA captures must not enter version control.
- Incoming intents to exported components are untrusted and must not trigger privileged or disruptive behavior without validation and current user authorization.
- Permission-flow continuation state must be app-private, expiring, one-shot, and excluded from backup and device transfer.

## Platform limitation

The Samsung methods used by this project are undocumented. A successful method invocation is not proof of privacy against screenshots, screen recording, external cameras, accessibility services, malicious overlays, or future firmware changes. Treat this app as experimental, not as a certified security boundary.
