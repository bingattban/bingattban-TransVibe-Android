# TransVibe: official signed Android release (1.0.1 / versionCode 2)

The canonical Android package identity remains `com.transvibe.app`.
The repository is **public**, so the owner's private JKS/keystore is never
committed or uploaded as a GitHub artifact.

## GitHub Actions repository secrets

Exactly four Actions secrets are expected, configured by the repository owner:

- `ANDROID_KEYSTORE_BASE64` — complete Base64 encoding of the original JKS,
  not the file path or a text password
- `ANDROID_KEYSTORE_PASSWORD` — the keystore password
- `ANDROID_KEY_ALIAS` — the alias of the private signing key inside the JKS
- `ANDROID_KEY_PASSWORD` — the private key entry password

Never post these values in an issue, chat, commit, screenshot, public build log,
Gradle file, or pull request. Keep a separate safe backup of the original JKS
and passwords; a public certificate hash alone cannot restore the private key.

## Tests first, release after trusted main

1. Every PR runs the existing [Android Tests First and ARM64 APK](../.github/workflows/android-ci.yml)
   workflow plus the free emulator smoke workflow. Both must pass before merge.
2. The main CI job runs source/JNI tests, Kotlin unit tests and Android Lint
   before building debug APKs **and validating an unsigned Release with R8**.
3. After successful merge, main CI runs again on the main SHA. Only after a
   **successful push-to-main** full CI does
   [Signed TransVibe Release](../.github/workflows/signed-android-release.yml)
   run. It verifies that the source is still the current main SHA, restores the
   owner's JKS to temporary private storage, signs the ARM64 Release APK,
   validates its signing certificate with `apksigner` and `keytool`, and
   checks application ID and version.
4. GitHub Actions **Artifacts** from the signing workflow contain:
   `TransVibe-v1.0.1-arm64-signed.apk`, `SHA256SUMS`, and `BUILD_INFO.txt`.
   No keystore or password is included. The artifact expires after 30 days.
   This workflow does **not** automatically publish a GitHub Release or Play
   Store listing.
5. Failed tests, an absent/wrong password/alias/JKS, a changed main SHA, a
   certificate mismatch, or APK verification failure must **block publication**.

The signing secrets are **never available to pull request workflows**.
The public Actions jobs only test the signing contract statically.
The real signing operation and certificate verification cannot be validated
until trusted main CI completes on the merged commit.

## Existing GitHub Debug installation

The owner previously installed the GitHub Actions `app-debug.apk`.
That APK was likely signed using an ephemeral CI Debug keystore **different**
from the personal JKS. Therefore a first official signed Release may report
"package conflicts with an existing package" when installing over it.

**Protect data before migration**:

1. Export existing subtitles/projects and copy downloaded model files to a
   user-accessible folder (verify the files can be opened independently).
2. Be particularly careful because AndroidManifest has
   `android:allowBackup="false"`; normal OS backup may not restore
   the application's private data.
3. After confirming the backups, uninstall the old GitHub Debug build if its
   signer differs, then install the official signed Release **once**.
4. Future official versions signed with the **same private key**, same package
   ID, and higher `versionCode` can update in-place, preserving app data in
   normal circumstances. Future release versions must explicitly increment
   `versionCode` above 2; do not auto-generate a higher number for repeated
   builds of the same source commit.

Do not uninstall an installed Release signed with the same certificate:
that would unnecessarily delete its app-private data. The certificate SHA-256
published in `BUILD_INFO.txt` can be checked against the keystore's public
certificate fingerprint. **Never** confuse this public hash with the key.

## Scope and limitations

The signed Release is CPU-based ARM64 and contains the same pinned JNI/AAR/JAR
libraries as the debug version. It is not evidence of faster Whisper inference.
The repository's DotProd variant remains experimental and is not shipped.
FFmpegKit `min-gpl` may have GPLv3 source/distribution obligations; review
these before distributing APKs outside private testing.
