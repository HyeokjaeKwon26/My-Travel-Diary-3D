# Development and maintenance

For installation and everyday use, see the [user guide](INSTALLATION_3D.md). This page contains build, signing, and implementation references for contributors.

## Project identity

My Travel Diary 3D is an independent edition of [My Travel Diary](https://github.com/HyeokjaeKwon26/My-Travel-Diary), based on original commit `9bcfb4f`. Original attribution and history are retained under AGPL-3.0.

- New application ID: `com.traveler.threed.next`; the original `com.traveler.threed` and 2D apps remain separate.
- Current New edition: version `2.1.0`, version code `2`; updates New 2.0.0 using the same dedicated key.
- Minimum Android version: 8.0 (API 26).
- No automatic import of the original app's private storage.

## Build and validation

Requires JDK 17 and Android SDK platform 36. Open in Android Studio or run:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
# With an Android emulator/device connected:
./gradlew connectedDebugAndroidTest
```

On Windows use `gradlew.bat`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds an APK and uploads unit-test/lint reports for pushes and pull requests. Instrumentation covers rendering, MP4 export/decoding, audio, cancellation, gallery storage, adaptive UI and data persistence. Emulator results do not establish physical-device performance, thermal behavior, or sustained frame rate. See [production status and validation evidence](PRODUCTION_STATUS.md).

## Maintainer signing

`assembleRelease` uses ignored `keystore.properties` when available. Without it, CI produces an unsigned release. Keep the same key for compatible updates and increase the version code. Never commit the key or credentials.

Example configuration (replace placeholders locally):

```properties
storeFile=C:/private/traveler3d-release.jks
storePassword=YOUR_PASSWORD
keyAlias=traveler3d
keyPassword=YOUR_PASSWORD
```

The current New edition's production key is stored outside the checkout under `%USERPROFILE%/.android/traveler-next-signing`, with credentials in ignored `keystore.properties`. Back it up securely. This key cannot update the original 3D app in place; use the [side-by-side migration procedure](SIDE_BY_SIDE_MIGRATION.md). Current features and update behavior are described in [2.1.0 features](ENHANCEMENTS_2_1.md).

## Technical references

- [Production status](PRODUCTION_STATUS.md): current behavior, validation and remaining acceptance work.
- [Implementation status](IMPLEMENTATION_STATUS.md) and [3D roadmap](3D_TRAVEL_ROADMAP.md): architecture and development history.
- [Terrain packs and attribution](TERRAIN_PACKS.md), [base map source](BASEMAP_SOURCE.md), [music licenses](MUSIC_LICENSES.md).
- [Privacy and data handling](PRIVACY_3D.md): network requests, photo references, caches, backups and export privacy.

Older inherited documents and screenshots may describe the original 2D app. They are not evidence of current 3D behavior or performance.

## Public documentation

Keep the [README](../README.md) and [user guide](INSTALLATION_3D.md) focused on benefits, real screenshots, download links and practical steps. Put implementation details, build commands and test reports in developer documents. When publishing a version, check download links, button labels and screenshot relevance. Label illustrative data and do not promise full offline maps, retained photo originals or device performance that has not been implemented or verified.

Update the [English README](../README.en.md), [English user guide](INSTALLATION_3D.en.md), and both languages of the [Disclaimer](../DISCLAIMER.md) alongside their Korean counterparts. Preserve license and source credits. Creator information must be supported by an identifiable source; do not infer a biography from the account name.

Current source update: [optional media storage and portable ZIP backups](PORTABLE_MEMORIES.md).

In-app update implementation and release metadata generation: [IN_APP_UPDATES.md](IN_APP_UPDATES.md).
