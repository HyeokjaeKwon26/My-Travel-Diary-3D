# Installation and signing

## New installation

- Download **My-Travel-Diary-3D-1.0.0-rc6-arm64.apk** for Galaxy S23 Ultra and other ARM64 devices. **My-Travel-Diary-3D-1.0.0-rc6.apk** is the universal alternative for supported 32-bit ARM and x86 devices. Both use the same production signing key and release optimization; install only one.
- Open the downloaded APK on your phone and allow installation from that source if Android asks.
- Open My Travel Diary 3D and import your Timeline and photos. On Wi-Fi, the app prepares terrain around your journeys; pin a journey to keep its terrain for offline use.
- The original 2D app (`com.traveler`) is separate and can stay installed. The 3D edition uses `com.traveler.threed`.

## Updating an earlier 3D release

Install either compatible RC6 APK directly over an earlier production 3D version. The application ID and signing key are unchanged and the version code increases to 7. Keep the installed app: uninstalling is unnecessary and would remove its local data. Existing journeys and terrain remain available.

The earlier 3D alpha had no installations or users; no alpha migration procedure is needed.

## Map detail and camera

The camera always keeps north at the top while following the current location. Vehicles still face their travel direction. The route length and available screen aspect determine the scale automatically; manual scale and pinch zoom are not used. The same camera calculation frames exported videos. Intro/outro show the whole route, and long movements use a broader view.

Internet street detail is on by default and uses Wi-Fi or mobile data for the visible area only. Disable it in the same dialog to use cached detail and the bundled regional fallback. The street cache is capped at 96 MiB, separate from the elevation cache; it is not an offline map pack. Video export uses already cached street detail without fetching new tiles. GPS records and road matching are unchanged. Street-map loading no longer pauses story time, photos or music. Raster and map-base preparation run off the live render thread; export still waits for its own deterministic cached map frame. The 2D renderer clips all drawing to its viewport.

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

## Video and screen size

Choose Portrait (9:16) or Landscape (16:9) before exporting, then 720p or 1080p. Rotating the playback screen preserves the encoded aspect and uses letterboxing; it does not regenerate or crop the video. The in-app Full screen button changes orientation. Camera framing adapts to the chosen export aspect. Smaller/large-font diary layouts stack content to keep labels readable.

The image-labeling model is bundled for local, offline photo analysis. This increases APK size; the ARM64 APK omits other CPU runtimes to reduce the download. It does not contain a global terrain or street-map pack. The bundled ML Kit SDK can send device/app/usage diagnostics; see [privacy](PRIVACY_3D.md).
