# Releasing

Pushing a tag that starts with `v` builds a release APK and attaches it to a GitHub release:

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

Bump `versionCode` and `versionName` in `app/build.gradle.kts` first.

## Signing

Without any configuration the release APK is signed with the debug key. It installs, but
Android will not treat a later build signed with a different key as an update of it.

For a stable identity, create a keystore once and keep it somewhere safe:

```bash
keytool -genkeypair -v -keystore release.jks -alias petcheck -keyalg RSA -keysize 4096 -validity 10000
```

Then add these repository secrets under **Settings > Secrets and variables > Actions**:

| Secret | Value |
| --- | --- |
| `PETCHECK_KEYSTORE_BASE64` | the keystore file, base64 encoded (`base64 -w0 release.jks`) |
| `PETCHECK_KEYSTORE_PASSWORD` | the keystore password |
| `PETCHECK_KEY_ALIAS` | `petcheck` |
| `PETCHECK_KEY_PASSWORD` | the key password |

To sign a build on your own machine, set `PETCHECK_KEYSTORE_FILE` to the keystore's path
along with the other three variables, then run:

```bash
./gradlew assembleRelease
```

## Firebase in released builds

A release built by the workflow has no `google-services.json`, so it is a standalone
monitor with no remote viewing.

Adding a `GOOGLE_SERVICES_JSON` secret (the contents of the file) builds remote viewing in.
Think before doing that for a public release: everyone who installs that APK would use your
Firebase project and its quota. For a project other people will install, the usual choice
is to leave it out and let each person build with their own project.
