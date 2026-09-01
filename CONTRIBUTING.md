# Contributing

Thank you for improving Privacy Box. Contributions are accepted only when the following requirements are met.

## Legal requirements

1. Read [LICENSING.md](LICENSING.md).
2. Complete [CLA.md](CLA.md) using the Project Owner's designated private submission method. Never put a postal address or handwritten signature in a public issue or pull request.
3. Certify every commit under the [Developer Certificate of Origin 1.1](https://developercertificate.org/) by adding a sign-off:

   ```sh
   git commit -s
   ```

   The resulting commit message must contain `Signed-off-by: Your Legal Name <your-email@example.com>`.

The CLA is the durable copyright and patent grant. DCO sign-off records contribution provenance; it does not replace the CLA.

## Technical requirements

- Keep changes narrowly scoped.
- Do not commit credentials, signing keys, `local.properties`, APKs, logs, device dumps, or private screenshots.
- Preserve locked-mode touch-through behavior and explicit user controls.
- Treat the reflected Samsung methods as unsupported and device-specific; do not claim compatibility without physical-device evidence.
- Include tests or reproducible verification appropriate to the change.
- Describe exact device and firmware evidence for changes to Privacy Display behavior.
- Keep `app/gradle.lockfile`, `gradle/verification-metadata.xml`, and `THIRD_PARTY_NOTICES.md` synchronized during reviewed dependency changes. CI must never regenerate trust metadata.
- Run `tools/verify_wrapper.sh`, `tools/verify_third_party_notices.py`, and the strict Gradle checks documented in [docs/RELEASE.md](docs/RELEASE.md).

## Pull requests

Each pull request must state:

- what changed and why;
- security and privacy impact;
- tests and physical-device checks performed;
- whether new permissions, exported components, network access, telemetry, or third-party dependencies were added; and
- whether every commit has a DCO sign-off and the contributor's CLA is on file.

The verified project owner is `moni11811`. Automated CLA/DCO enforcement is not configured, so contributions must not be merged until the owner has privately confirmed the CLA record and every commit has a valid DCO sign-off.
