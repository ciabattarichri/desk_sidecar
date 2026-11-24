# Releases

Each `v<version>/` folder is a self-contained bundle produced by `scripts/make-release.sh`:

| File | Content |
|---|---|
| `desk_sidecar-<version>.apk` | the app (R8-optimized, signed with the debug key — personal sideload) |
| `pc-tools-<version>.tar.gz` | `pc/` (phonedash, service template, installer), `phone/` scripts, `docs/` |
| `dms-plugin-<version>.tar.gz` | the DMS bar plugin (`phoneDash/`) |
| `case-<version>.tar.gz` | FreeCAD macros and STEP models |
| `SHA256SUMS` | checksums (`sha256sum -c SHA256SUMS`) |

Install from a bundle:

```sh
sha256sum -c SHA256SUMS
adb install -r desk_sidecar-<version>.apk
tar -xzf pc-tools-<version>.tar.gz && pc/install.sh
```

then follow [docs/setup.md](../docs/setup.md) from step 2 (permissions).

## Making a release

1. bump `versionCode` and `versionName` in `app/build.gradle.kts`;
2. `scripts/make-release.sh`;
3. commit/tag as you like (bundles are small; keep them in git or attach them to a release page).
