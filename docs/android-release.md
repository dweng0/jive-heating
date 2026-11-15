# Android release builds

The app is sideloaded and updates itself from the service (Account shows "Up to date" or offers the new version).
Android only installs an update signed with the same key as the installed app, so every release build is signed with
one stable key.

## The key

- The key file and passwords are never in git. Release builds read four environment variables:
  `HEATING_RELEASE_KEYSTORE` (path), `HEATING_RELEASE_STORE_PASSWORD`, `HEATING_RELEASE_KEY_ALIAS`,
  `HEATING_RELEASE_KEY_PASSWORD`.
- Without them a release build stops with a message naming what is missing. It never falls back to the debug key.
- The household's key is the one the first phones were installed with (the build machine's Android debug key), kept at
  `~/.config/jive-heating/release.keystore` with its settings in `~/.config/jive-heating/release.env`. Keeping it
  meant no phone needed reinstalling. Back both files up: losing the key means every phone must uninstall and
  reinstall the app.

## Releasing

1. Raise `versionCode` (and `versionName`) in `android/app/build.gradle.kts`.
2. Run `android/release.sh`. It builds the signed APK and copies it and Gradle's `output-metadata.json` into
   `android-dist/`.
3. Rebuild and restart the service's Docker image. `GET /api/app` then reports the new version, and phones offer it.
