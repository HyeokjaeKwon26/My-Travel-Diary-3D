# 1.0.0-rc2 implementation and acceptance status

Date: 2026-09-15. Target phone: Galaxy S23 Ultra. The user could not connect the phone during this session. This release candidate can be installed and used, but it is not a declaration that every criterion in the production readiness plan has passed.

## RC2 map correction

The user's S23 Ultra screenshot showed an opaque brown terrain surface with only the route and vehicle visible. RC1 rendered elevation meshes without cartography and covered its low-resolution globe texture. RC2 drapes bundled land/water, regional roads, rivers, urban areas and place labels over the elevation mesh. A local base surface keeps cartography visible when no DEM is stored. The globe coordinate handedness was also corrected so east/west and map text are no longer mirrored.

Map data uses a preprojected binary cache and one 2048×2048 local texture (16 MiB on the GPU), refreshed when the camera leaves its central area or zoom changes. It works offline and is shared by playback and video export. Natural Earth is a regional reference map, not a complete street map or road-matching service. APK version code 3 uses the same release signing key as RC1.

### RC2 verification

- 296 JVM unit tests passed, including geographic east/right and heading orientation regression coverage.
- Five Android tests passed: isolated GLES pixel checks for San Francisco with/without elevation and inland Phoenix roads/labels, plus the three existing 3D journey/playback/export/persistence checks. Wi-Fi was disabled and airplane mode was enabled.
- The new tests caught both the initial missing packaged asset name and insufficient road contrast; the final build passed after fixing these. The exported Grand Canyon MP4 decoded completely with FFmpeg.
- Final optimized, signed APK: 43,801,381 bytes; SHA-256 `250e1193c40ee8ecc0b2d8bb33c2c7143a1b66d77bd4106b664602d61ff2e71c`. Packaged JNI/resource/cartography checks passed; lint has 0 errors and 85 warnings.
- Installed signed RC1, saved a sample journey, installed RC2 directly over it, and reopened the retained 31.2 km journey and played its mapped 3D terrain offline. [Final signed-app capture](verification-3d/rc2-signed-play.png).
- Earlier emulator screenshots included System UI/Pixel Launcher non-response dialogs and are not used as clean visual evidence. The final signed-app capture was taken after restarting the emulator and dismissing the external system dialog. These emulator checks remain functional evidence, not physical-phone performance acceptance.

## Implemented

- Automatic WorkManager downloads of public terrain tiles around ground paths and visits; Wi-Fi default, explicit mobile-data option, pause/resume, retries, persisted manifests, bounded responses and local checksums.
- GZIP metre grids shared across journeys, 100/200/500 MB cache limits (200 default), oldest-first eviction, offline pins, explicit clear action, and free-space checks.
- Tile-border stitching, spatial height lookup, camera clearance, route/vehicle alignment with rendered triangle heights, and conservative treatment of missing/steep elevation. Downloaded elevation is not mixed with a different GPS datum within a ground route.
- At most 12 nearby terrain meshes on the GPU side; route densification scales down for long journeys. Screen resolution has render-cost hysteresis and thermal reduction. Playback requests are coalesced to approximately 30 fps without dropping the final seek.
- 1080p and 720p video choices, codec capability checks, streamed AAC soundtrack decoding, bounded EOS draining and final muxer validation. Music asset reduced from 31,325,228 bytes WAV to 2,893,164 bytes M4A.
- Journey backup/restore with bounded input and local media reference validation. Production signing and release shrink/obfuscation. Original 2D repository and app identity preserved.
- Offline source credits and updated privacy/installation documentation.

## RC1 verification history

- JVM unit tests: 295 passed, 0 failures/errors.
- Android API 36 emulator: all 17 terrain/cache, 3D, video and Room migration tests passed. Five affected integration cases were rerun after final cache and audio-test changes; all passed.
- Debug lint: 0 errors, 85 warnings and 5 informational findings (includes existing project warnings).
- `assembleRelease`: passed with shrinking and production signing; APK 33,910,114 bytes (33.91 MB decimal).
- Only the production-signed APK is distributed for RC1. The owner confirmed no earlier 3D alpha installations, so the unnecessary alpha compatibility asset and migration instructions were removed. Historical local validation records may still include that unused build.
- Signed release installation and real UI smoke: Timeline JSON import, 46-region automatic terrain download, offline pin, forced process stop, airplane-mode restart, saved-terrain playback, 720p AAC/H.264 export and gallery save all passed. Exported 8.42-second MP4 was decoded completely with FFmpeg (250 video frames, stereo AAC).
- Release-only import crashes found in zstd JNI field lookup and ESRI class-relative resource lookup were fixed with targeted package preservation. `tools/verify_release_jni.py` inspects the actual packaged DEX/resources and rejects the earlier broken APK; the final APK passes.
- GitHub CI was still running when this local verification record was finalized; no remote green-check claim is made. Local tests/build and signed-APK smoke are the evidence above.

 Tests use synthetic trips and public terrain coordinates, not personal travel history. Emulator timings are not S23 Ultra benchmarks.

## Remaining acceptance and features

- No physical S23 Ultra / other-phone 20-minute stress run, frame-time p95 measurement, thermal acceptance, or manufacturer-specific background behavior test was possible.
- The actual user-reported two-line route case has not been supplied. Synthetic duplicate/return/continuity tests and shared route rendering do not prove that particular case is fixed.
- Existing timeline canonicalization and diagnostics remain in place. A comprehensive new per-point horizontal GPS-error classifier and user-facing elevation/bridge/tunnel editing are not implemented.
- Terrain LOD is chosen per journey, not continuously refined coarse-to-fine. Raw height grids for up to 256 regions are retained on the CPU; only nearby meshes are built. Strict view-frustum culling and asynchronous mesh upload remain optimization work.
- Heading smoothing comes from the existing common timeline. Further camera transition work for extreme gaps and mode switches remains; rewind is deterministic but is not universally cinematic.
- No road matching, detailed buildings, measured bridge deck/tunnel altitude, global terrain package, provider uptime guarantee, or photo migration across phones is claimed.
- Full power-loss/storage-exhaustion/permission-revocation/background stress across supported Android releases remains acceptance work. Backup does not include image files, permission grants, terrain or original import files.

These distinctions remain visible in release notes. RC1 is shipped to enable real use and phone acceptance without calling unverified criteria complete.
