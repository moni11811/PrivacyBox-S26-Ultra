# Release-candidate process

This public repository contains the source for the signed v1.0.5 release. The release-candidate workflow is deliberately non-publishing and private-staging-only: it produced and verified the signed candidate before the repository became public, after which the allowlisted release bundle was published separately.

## Pinned build inputs

- Gradle wrapper and distribution: 9.1.0.
- Official wrapper JAR SHA-256: 76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3.
- Official binary distribution SHA-256: a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806.
- CI JDK: Eclipse Temurin 17.0.20+8 (runtime `java.version` 17.0.20).
- Android Build Tools and apksigner: 36.0.0, selected by an exact path and checked before secrets are used.
- Runtime dependencies: strict Gradle locks plus SHA-256 dependency verification metadata.
- GitHub Actions: immutable full commit SHA pins with version comments.
- Release signing owner: `moni11811`; key alias: `privacybox-release`.
- Approved release certificate SHA-256: `f707875a17cfd534f12b4d18479e14b6321e42226a05a9c2d7ec1d7bb2a74306`.

Official references:

- <https://gradle.org/release-checksums/>
- <https://docs.gradle.org/9.1.0/userguide/gradle_wrapper.html>
- <https://docs.gradle.org/9.1.0/userguide/dependency_verification.html>
- <https://docs.gradle.org/9.1.0/userguide/dependency_locking.html>
- <https://github.com/actions/setup-java>

## Continuous verification

The CI workflow performs these gates without regenerating trust metadata:

1. check out the exact revision without persisted credentials;
2. install the exact Temurin JDK and verify vendor/version;
3. validate wrapper JAR and distribution pins before executing Gradle;
4. scan history and the working tree with Gitleaks;
5. parse every workflow run scalar and reject any direct or nested workflow input expression;
6. verify that notices match locked release runtime dependencies;
7. run clean, unit tests, Android lint, debug/release assembly, and test-APK assembly under strict dependency verification and locking.

## Release candidate

The release-candidate workflow is manual and must itself be dispatched from the same protected annotated tag named by source_tag. The repository must still be private. The workflow never creates a GitHub Release, publishes a package, or uploads to Play.

The v1.0.5 release used this configuration:

- standalone repository `moni11811/PrivacyBox-S26-Ultra` with protected `v*` tags and protected main history;
- release-candidate environment secrets with `moni11811` as the required reviewer;
- verified public owner identity `moni11811` in the owner, license, and contribution records;
- completed and approved `ARTWORK_PROVENANCE.md` for the ChatGPT-generated icon;
- preserve the selected permanent application ID `com.moni11811.privacybox` and review any future package-owned identifier changes;
- preserve the `moni11811` signing owner and independently verify the approved certificate SHA-256 above;
- private CLA record confirmation and DCO sign-off checks before accepting contributions;
- owner review of the license, CLA, notices, and artwork declaration; no independent legal opinion is claimed.

Protected environment secret names:

- ANDROID_RELEASE_KEYSTORE_B64
- ANDROID_RELEASE_STORE_PASSWORD
- ANDROID_RELEASE_KEY_ALIAS
- ANDROID_RELEASE_KEY_PASSWORD
- APPROVED_SIGNING_CERT_SHA256
- GITLEAKS_LICENSE when the repository owner type requires it

The raw dispatch tag is passed into Bash only through an environment variable. Before repository code runs, the workflow restricts it to the version-safe character set, requires it to equal the run's exact `refs/tags/` reference, requires a direct annotated tag object targeting a commit, and binds tag object, commit, tree, and deterministic source-archive digest. Tags with export-ignore or export-subst attributes are rejected. Only validated step outputs are used by later shell steps and artifact naming. The clean source is rechecked immediately before and after the build.

The build job uploads an explicit, checksum-bound one-day unsigned handoff. A fresh environment-protected signing job executes no checked-out repository code: it independently checks out the protected tag, recreates and byte-compares Corresponding Source, rejects extra files and symlinks, and revalidates every identity and digest before receiving signing secrets. It decodes the keystore only to a randomized runner-temporary file, keeps passwords in environment-backed apksigner inputs, selects Build Tools 36.0.0 by exact path, and verifies the approved public certificate digest without logging certificate identity. The final seven-day artifact is an explicit allowlist containing only the signed APK, matching source, identity, notices, license, and checksums.

The candidate workflow fails if the repository is public, so future signed candidates require the same private-staging procedure or a separately reviewed workflow-policy change. Temporary Actions artifacts were deleted after v1.0.5 verification. The public GitHub Release contains only the signed APK, checksums, source identity, matching Corresponding Source, notices, and Apache license under the protected immutable tag.

## Local verification

Run with an installed JDK 17:

    tools/verify_wrapper.sh
    python3 tools/verify_third_party_notices.py
    python3 -m unittest tools/test_verify_workflow_run_inputs.py
    python3 tools/verify_workflow_run_inputs.py
    ./gradlew --dependency-verification=strict --no-daemon --no-configuration-cache clean test lint assembleDebug assembleRelease assembleDebugAndroidTest

Never run the metadata-generation options in CI. Regenerate locks or verification metadata only during a reviewed dependency update, then inspect the resulting versions, repositories, licenses, and hashes before accepting them.
