#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

wrapper_hash_file="gradle/wrapper/gradle-wrapper.jar.sha256"
wrapper_jar="gradle/wrapper/gradle-wrapper.jar"
properties="gradle/wrapper/gradle-wrapper.properties"
expected_wrapper="$(awk '{print $1}' "$wrapper_hash_file")"
test "$expected_wrapper" = "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
if command -v sha256sum >/dev/null 2>&1; then
  actual_wrapper="$(sha256sum "$wrapper_jar" | awk '{print $1}')"
else
  actual_wrapper="$(shasum -a 256 "$wrapper_jar" | awk '{print $1}')"
fi

test "$actual_wrapper" = "$expected_wrapper"
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip' "$properties"
grep -Fqx 'distributionSha256Sum=a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806' "$properties"
grep -Fqx 'validateDistributionUrl=true' "$properties"
test -x gradlew
test -f gradlew.bat

echo "Verified Gradle 9.1.0 wrapper JAR and distribution pins."
