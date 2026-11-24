#!/usr/bin/env bash
# Builds a release bundle in release/<version>/:
#   desk_sidecar-<version>.apk   optimized (R8) app, signed with the debug key (personal sideload)
#   pc-tools-<version>.tar.gz      phonedash CLI, systemd unit template, installer
#   dms-plugin-<version>.tar.gz    DMS bar plugin
#   case-<version>.tar.gz          FreeCAD macros + STEP models
#   SHA256SUMS
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
OUT="release/v$VERSION"
./gradlew --console=plain -q assembleRelease
rm -rf "$OUT" && mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/desk_sidecar-$VERSION.apk"
tar -czf "$OUT/pc-tools-$VERSION.tar.gz" LICENSE.md THIRD_PARTY_NOTICES.md pc phone docs
tar -czf "$OUT/dms-plugin-$VERSION.tar.gz" LICENSE.md -C dms-plugin phoneDash
tar -czf "$OUT/case-$VERSION.tar.gz" LICENSE.md case/*.FCMacro case/*.step
cp LICENSE.md THIRD_PARTY_NOTICES.md "$OUT/"
(cd "$OUT" && sha256sum -- * > SHA256SUMS)
echo "Release ready in $OUT:"; ls -la "$OUT"
