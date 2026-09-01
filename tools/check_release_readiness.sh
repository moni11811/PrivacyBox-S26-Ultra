#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  echo "Release blocker: $1" >&2
  exit 1
}

if grep -Eq '\[PROJECT OWNER LEGAL NAME\]' CLA.md NOTICE.md; then
  fail "verified project-owner legal identity is still missing from CLA.md or NOTICE.md"
fi
if grep -Eq 'applicationId = "com\.example\.privacybox"' app/build.gradle.kts; then
  fail "the permanent application ID has not been selected"
fi
test -f ARTWORK_PROVENANCE.md || fail "ARTWORK_PROVENANCE.md has not been completed and approved"
python3 tools/verify_third_party_notices.py

approved_certificate="${APPROVED_SIGNING_CERT_SHA256:-}"
[[ "$approved_certificate" =~ ^[0-9A-Fa-f]{64}$ ]] ||
  fail "APPROVED_SIGNING_CERT_SHA256 is absent or not a 64-character SHA-256 digest"

for forbidden in local.properties '*.jks' '*.keystore' '*.p12' '*.pfx' '*.pem' '*.key' '*.apk' '*.aab' '*.log'; do
  if find . -type f -name "$forbidden" -not -path './.git/*' -not -path './app/build/*' -print -quit | grep -q .; then
    fail "forbidden release-tree file matched $forbidden"
  fi
done

echo "Release readiness checks passed."
