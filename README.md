# Privacy Box

Privacy Box is an experimental Android overlay for Samsung's partial Privacy Display feature. It creates a movable, resizable privacy region above other apps, adds a lock mode that passes touches through to the app underneath, and provides a Quick Settings tile.

## Hardware and compatibility

- Developed and physically tested on a Samsung Galaxy S26 Ultra.
- Requires Android API 36 and Samsung firmware exposing the undocumented `View` methods `semSetPrivacyDisplayView`, `semSetPrivacyDisplayViewPosition`, and `semDisablePrivacyDisplayView`.
- This is an unsupported Samsung implementation detail, not a public Android or Samsung SDK contract. A firmware update may rename, restrict, or remove it.
- Privacy behavior must be verified on the exact device and firmware before relying on it. This project makes no guarantee that content is invisible from every angle or capture path.

Samsung is a trademark of Samsung Electronics. This project is independent and is not endorsed by Samsung.

## Behavior

- Drag anywhere inside the unlocked privacy region to move it.
- Use the background-free resize icon at the lower-right to resize the unlocked region.
- Pause followed by Stop appear at the top-right; Pause disables privacy and collapses the box into an unlocked shield that resumes it when tapped.
- Lock and Resize stay at the lower-right. Hold Lock to lock or unlock.
- Locked mode hides the cyan editing border, removes the editing controls except the lock control, and allows touches to pass through the privacy layer.
- Use the stop control or Quick Settings tile to remove the overlay.

The app requests Android's display-over-other-apps permission and runs a foreground service while the overlay is present. It has no network permission, analytics, advertising, or account system.

## Build

Requirements:

- JDK 17
- Android SDK 36

```sh
tools/verify_wrapper.sh
python3 tools/verify_third_party_notices.py
./gradlew --dependency-verification=strict --no-configuration-cache clean test lint assembleDebug assembleRelease
```

The debug APK is generated under `app/build/outputs/apk/debug/`. APKs, signing material, machine-local SDK paths, and private QA screenshots are intentionally excluded from version control.

## Install for development

With the intended phone selected in `adb devices`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant the appear-on-top permission from the app, then enable the Privacy Box Quick Settings tile if desired.

## Security and privacy

See [SECURITY.md](SECURITY.md) for reporting instructions and [docs/SECURITY_ALIGNMENT.md](docs/SECURITY_ALIGNMENT.md) for the current Android security review. The overlay permission is powerful; install only builds you trust. Backup is disabled, and both Android backup-rule formats explicitly exclude the geometry, lock, error, and one-shot permission-flow preferences.

The latest signed APK and its exact matching source, checksums, notices, and license bundle are available from the [v1.0.5 release](https://github.com/moni11811/PrivacyBox-S26-Ultra/releases/tag/v1.0.5). See [docs/RELEASE.md](docs/RELEASE.md) for the wrapper, dependency, JDK, signing, tag, checksum, and Corresponding Source controls used to produce it.

## License and contributions

Project-authored source code is licensed under **GNU AGPL-3.0-or-later**. See [LICENSE](LICENSE) and [LICENSING.md](LICENSING.md).

Contributions require both the project CLA and a Developer Certificate of Origin sign-off. See [CONTRIBUTING.md](CONTRIBUTING.md) and [CLA.md](CLA.md). This policy protects the project's ability to keep the complete source available while preserving contributor ownership.

Runtime dependency notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The project and signing owner is **moni11811**, the permanent application ID is `com.moni11811.privacybox`, and project-authored source plus the documented ChatGPT-generated artwork are licensed under GNU AGPL-3.0-or-later. The approved release certificate is recorded in [docs/RELEASE.md](docs/RELEASE.md); private signing material is never stored in Git. Contribution intake remains subject to the CLA/DCO policy in [CONTRIBUTING.md](CONTRIBUTING.md).
