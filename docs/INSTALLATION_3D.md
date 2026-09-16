# Installation and signing

## New installation

- Download **My-Travel-Diary-3D-1.0.0-rc1.apk** from the release page (production signing key, release optimization, 33.91 MB). This is the only installation APK distributed for RC1.
- Open the downloaded APK on your phone and allow installation from that source if Android asks.
- Open My Travel Diary 3D and import your Timeline and photos. On Wi-Fi, the app prepares terrain around your journeys; pin a journey to keep its terrain for offline use.
- The original 2D app (`com.traveler`) is separate and can stay installed. The 3D edition uses `com.traveler.threed`.

The owner confirmed that the earlier 3D alpha had no installations or users. No alpha migration, backup, or uninstall step is required before this new installation.

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
