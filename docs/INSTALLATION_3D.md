# Installation and signing

## Which APK

- First install of the 3D edition: use **My-Travel-Diary-3D-1.0.0-rc1.apk** (production signing key, release optimization).
- Existing `v0.1.0-alpha` 3D installation: use **My-Travel-Diary-3D-1.0.0-rc1-alpha-update.apk** to preserve its data and add backup support. This is a debug-signed compatibility build; it cannot install over the production-signed build.
- The original 2D app (`com.traveler`) is separate and can stay installed. Both new APK variants use `com.traveler.threed` and cannot coexist with one another.

## Switching from the 3D alpha to production signing

1. Install the alpha-update APK over the existing 3D alpha; do not uninstall first.
2. In each journey’s Export dialog, choose **Back up this journey**. Keep Timeline source files and original gallery photos too.
3. Check that the `.travel3d.json` files were saved outside the app’s private storage.
4. Only after backing up all journeys, remove the debug-signed 3D app and install the release APK. Android requires this because the signatures differ.
5. Home → **Restore**, choose each backup, and grant photo access in Android app permissions. Local photo references are intended for the same phone; original images are not copied into backups.
6. Reopen journeys on Wi-Fi to prepare terrain again. Pin journeys for offline use.

No code automatically uninstalls the alpha or clears existing trips.

## Galaxy S23 Ultra

Start with 1080p export; 720p is available for shorter encoding time/lower memory use. Internal map resolution adapts to render cost and Android thermal signals independently from the chosen video resolution. These are controls, not a measured S23 Ultra performance guarantee. Physical phone testing was unavailable during this build.

## Maintainer signing

`assembleRelease` uses ignored `keystore.properties` when available. Without it, CI produces an unsigned release. Keep the same key for future production updates; losing it prevents compatible updates. Never commit the key, credentials, or debug signing identity.

Example configuration (replace placeholders locally):

```properties
storeFile=C:/private/traveler3d-release.jks
storePassword=YOUR_PASSWORD
keyAlias=traveler3d
keyPassword=YOUR_PASSWORD
```

This workstation keeps the production key outside the checkout at `%USERPROFILE%/.android/traveler3d-release.jks` and local credentials in ignored `keystore.properties`. Back these up securely. Release version codes must increase for future updates.
