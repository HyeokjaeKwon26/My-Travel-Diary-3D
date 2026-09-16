# 1.0.0-rc8 recorded-visit card summaries

Date: 2026-09-16. Home cards previously counted a cached list of at most five named places. They now derive `방문 기록 N회` from distinct saved visit source IDs, including unnamed stops and separate returns. A source spanning multiple days is counted once. A separate `대표 장소` preview lists up to three distinct normalized recorded names plus the remaining named entries. Specific names precede Home/Work; generic-only names alongside unnamed stops produce `장소 이름 정보 부족`. Partially named journeys identify their unnamed visit count.

One reactive Room query joins trips to just visit identifiers/names and durable name overrides. It observes all three tables, applies overrides to overlapping trips, and groups off the main thread. It loads no media, polyline geometry or timezone engines. The database schema and existing stored data are unchanged; no re-import is required. The derived summary is excluded from archive serialization. The legacy name cache remains for backward-compatible archives/titles but no longer supplies the home card. This counts recorded stops, not unique geographical destinations; it cannot recover visits missing from the original data.

Badge rows wrap for small screens and large fonts. Long titles leave room for Delete. Maps, playback and video rendering are unchanged.

## RC8 verification

- 324 JVM tests passed, with no failures/errors/skips. The six new cases cover unnamed/unknown names, repeated source IDs, revisits, more than five names, normalization/order and empty trips.
- Three targeted Android checks passed on an API 36 emulator. In-memory Room tests start with legacy Home-only caches, count eight actual records, retain zero-visit trips, observe renames across overlapping trips, compare home/detail summaries and preserve a two-day stay as one record plus a later return.
- A 320 dp card at 150% font scale keeps distance/photo/visit badges in bounds without overlap; the delete button and representative names remain visible. Screenshot inspected: [large-font card](verification-3d/rc8-card-large-font.png).
- Debug update over the existing production-signed installation retained the 31.2 km and 835.5 km sample journeys. Their existing records now show two and five visits respectively; distances and photo totals remain unchanged.
- JVM/debug/instrumentation builds and lint completed successfully (0 errors, 86 warnings, 8 informational findings). GitHub Android validation, including optimized release packaging, passed for application source `bffc43b` (run `35130803993`). Physical S23 Ultra testing remains unavailable.

- The final production-signed universal APK installed over the existing app without removal. Both saved journeys, distances, photo totals and corrected visit counts survived process restart and resizing from phone (1080×2400 / 420 dpi) to tablet (2560×1600 / 240 dpi) and back. Screenshots inspected: [signed phone](verification-3d/rc8-signed-home-phone.png), [signed tablet](verification-3d/rc8-signed-home-tablet.png). An external emulator System UI ANR at cold boot was dismissed before acceptance; it was not an app ANR.

## RC8 packages

Application source: `bffc43b`. Both packages use `com.traveler.threed`, version code 9 / `1.0.0-rc8`, and the existing production certificate. APK ZIP alignment, 64-bit ELF 16 KiB alignment, expected ABI sets and native/map/timezone resource contracts pass.

- `My-Travel-Diary-3D-1.0.0-rc8.apk`: 89414779 bytes; SHA-256 `03c2c7ca2831874a75a97a3b9f66761ef594dc08a321ce345b0ac9c136300e61`.
- `My-Travel-Diary-3D-1.0.0-rc8-arm64.apk`: 55901569 bytes; SHA-256 `9d6aab14a6c134c6b925c1371aea96675e24cdb359d47e16200cc55d64c5dd56`.

---

# 1.0.0-rc7 playback overlays and date selection

Date: 2026-09-16. RC7 keeps the existing map source, terrain and camera. It adds compact playback information, a persistent actual date and calendar day number, full-aspect photos in app/video, and safe date-picker confirmation above Android navigation. The floating map options card is removed; settings move to the diary toolbar. Pausing/scrubbing at zero retains playback overlays until the user explicitly exits playback. Video title/end subtitles are fitted inside their cards.

Dates use saved episode timezone metadata and the saved trip start date. Movement keeps departure timezone until arrival and then uses the destination timezone; unknown zones are marked UTC. No per-frame geographic timezone lookup is added. No route prefetch, provider migration or GitHub map pack is included.

## RC7 verification

- Lint: 0 errors, 86 warnings, 8 informational findings.
- 318 JVM checks passed, including local midnight, backward seek, title/end dates, phone-timezone independence, flight arrival across date boundaries, explicit unknown-timezone fallback and aspect fitting for portrait/landscape/panoramas.
- Eight distinct Android checks passed across targeted runs, including paused playback after seeking to zero. Android overlay checks cover a 150% font scale, non-overlapping date/info/photo bounds and the actual rendered pixels of all four photo borders. Video overlays preserve all four source edges in both 720×1280 and 1280×720 outputs.
- Date confirmation passed in gesture and three-button navigation at 1080×2400 / 420 dpi, and at tablet size 2560×1600 / 240 dpi. Confirmation is both above navigation and clickable.
- Existing 3D integration checks passed, including access to the relocated settings, normal demo rendering/export and stored geometry. Landscape export/player and photo-analysis integration passed. Portrait and landscape MP4s decoded completely with FFmpeg.
- Initial UI checks found that inherited Material line height made the small header too tall at 150% font scale, and Compose dialog insets alone left confirmation near system navigation. Explicit line heights and a union with host-window system insets fixed these failures. A screenshot initially captured before Coil's display frame; the final test waits for actual photo border pixels, not just updated layout bounds.
- An emulator System UI ANR dimmed a later photo screenshot and failed its exact-color check; this run is not counted as passing. The external system dialog was dismissed; the affected photo check and both exports then passed in a final three-test run. Both final MP4s decoded completely without errors.
- Physical Galaxy S23 Ultra testing remains unavailable. Emulator checks are not a hardware frame-rate or thermal guarantee. The previously reported video map gaps have no supplied MP4 reproduction; this release does not claim to fix an unverified map-renderer defect.

The production-signed universal APK was installed over the existing emulator installation and retained both saved journeys (31.2 km and 835.5 km). Playback, persistent date/day, relocated settings and an actual three-button-navigation confirmation tap passed. GitHub Android validation also passed for application source `2787e84` (run `35110346468`).

## RC7 packages

Application source: `2787e84` (later changes only extend tests and verification documentation). Both packages use `com.traveler.threed`, version code 8 / `1.0.0-rc7`, and the existing production signing certificate. APK ZIP alignment, 64-bit native ELF 16 KiB alignment, ABI sets and packaged JNI/map/timezone resources pass.

- `My-Travel-Diary-3D-1.0.0-rc7.apk`: 89,398,399 bytes; SHA-256 `50e254a54fb8afb3b9861eeb2a022eb00b88aef4e878bf5abfcd2cd57ae052e2`.
- `My-Travel-Diary-3D-1.0.0-rc7-arm64.apk`: 55,885,189 bytes; SHA-256 `191d78aa8c486cfbe5761f5cd7a04cad1bcc7658bd67e2ad9c91dc043a1c41e6`.

Screenshots: [signed playback](verification-3d/rc7-signed-playback.png), [signed three-button date picker](verification-3d/rc7-signed-date-picker.png), [tablet date picker](verification-3d/rc7-date-tablet.png).

---

# 1.0.0-rc6 implementation and acceptance status

Date: 2026-09-16 (local). Target phone: Galaxy S23 Ultra. The user could not connect the phone during this session. This release candidate can be installed and used, but it is not a declaration that every criterion in the production readiness plan has passed.

## RC6 playback and map bounds correction

The user's 2D screenshot showed map outlines behind the diary. The shared parent intentionally had no Compose clipping because that previously hid the GLES SurfaceView, but the native 2D renderer had no separate clip. `TravelMapRenderer.render` now saves/restores Canvas state and clips every draw to its viewport. Sparse long flight strokes are sampled along the same spherical interpolation as the marker, including flights with more than two recorded points. Recorded data remains untouched. The diary also paints its own background; 3D's SurfaceView parent remains unchanged.

Removed the network buffer gate and GPU map-readiness gate from live story/music playback. Missing tiles are a status only. Live atlas rasterization, tile compositing and local base-mesh construction run on a single bounded worker. Pending work is replaced with the newest viewport; stale completed viewports are discarded. Tile coverage compares sets, not request order. Socket cancellation is dispatched to IO and live status does not acquire the atlas painting lock on the render thread. Reference rasters, base meshes, bitmap buffers and GPU texture storage are reused. Preparation is coalesced to at most five starts per second and completion wakes a paused view. Exports retain synchronous frame preparation with the same map projection and frozen cache.

The renderer still uses a 2048-pixel atlas and the same detailed-map source. There is no route prefetch, paid provider, new permission, database schema change, or change to recorded travel data. Version code 7 uses the existing production signing identity. The original 2D repository is untouched.

A late signed-APK check found a real `OutOfMemoryError` with a 192 MiB app heap after switching between 2D/3D and creating the Boston/Niagara sample. The 2D loader independently parsed the large regional JSON while 3D retained the same map. Both now share one binary cache and identical coordinate arrays. That improvement alone did not resolve the crash: another signed run located the allocation failure in TimeShape's worldwide protobuf/polygon decoding. A 137,267-byte catalog generated from the pinned TimeShape 2026b.29 data now selects candidate archive entries before decoding; one regional engine is retained at a time, with serialized queries. The original full-resolution boundaries, timezone IDs, offshore semantics and map detail are preserved. No larger Android heap is requested.

Changing developer display size and density together recreated the activity and once left the new GLES surface blank until the map was selected again. MainActivity now handles density and font-scale changes in place, as it already does orientation and window size; Compose receives the new configuration while retaining the map surface and playback state.

### RC6 verification

- 313 JVM tests pass, including display-only interpolation for sparse multi-point flights and exact comparison with the original worldwide timezone engine at 16 city/border/ocean/dateline coordinates. Lint: 0 errors, 87 warnings.
- 25 distinct Android checks pass across the targeted runs: the existing 16 checks, four boundary/worker/socket regressions, a moving GLES cadence check, immediate retry after cancellation, actual offline story progress, shared 2D/3D map arrays, and timezone resolution alongside loaded maps with a verified 192 MiB heap limit. The final map-sharing subset passed 10 checks; the subsequent timezone-memory check passed separately in 9.6 seconds. Debug Boston/Niagara creation also completed after the timezone correction.
- With device Wi-Fi/mobile data off and the test street cache removed, story progress increased on every sample: `0.3000 → 0.3002 → 0.3419 → 0.3536 → 0.3652 → 0.3774`; user pause settled at `0.3804`. Playback is no longer gated by missing tiles.
- Moving-viewport diagnostic with a fake server delaying each response by 1.2 seconds: 120 frames completed during six slow requests. Software-emulator render+GPU-finish costs: median 19.1 ms, p95 42.7 ms, maximum 626.7 ms; maximum frame-start gap 662.6 ms, including startup. The harness deliberately sleeps 34 ms between draws. These are neither a sustained 30 fps claim nor S23 hardware timings.
- Generated portrait and landscape MP4s both decoded completely with FFmpeg. Existing audio/no-audio, cancellation, MediaStore/share, local photo analysis and player-rotation tests pass.
- Test-harness corrections: old shared-Downloads artifacts belonged to an earlier installation and caused EACCES; diagnostic files now use the app-specific external directory. A short synthetic playback finished before the pause assertion, and manually frozen Compose frames made map-toggle display assertions unreliable. The final automated clock test uses a longer route, settles pause and tests offline progress only. Production-APK map switching is checked separately below; the discarded toggle assertion is not counted as a passing check.
- An emulator System UI ANR appeared at cold boot and was dismissed before UI checks. No app ANR was observed in completed acceptance runs. No physical-phone/thermal benchmark is claimed.

### RC6 packages

Application source: `ab47f1c3059b66ef203e620e3c0b98056a2bdbc6` (subsequent changes are CI scheduling and verification documentation only). The final configuration-only rebuild preserves every DEX byte from the timezone-verified APK.

- `My-Travel-Diary-3D-1.0.0-rc6.apk`: 89,398,287 bytes; SHA-256 `3df782ada1ac83d547da1573c27754be51ae3b03aeb862ef512ec5a7898594bf`.
- `My-Travel-Diary-3D-1.0.0-rc6-arm64.apk`: 55,885,077 bytes; SHA-256 `4ee9fc6163737e4543a10db76c323c9d77e548ea39e609811e625f683308705f`.

Both APKs report `com.traveler.threed`, version code 7 / `1.0.0-rc6`. Production certificate SHA-256: `4f3995f13853b8b0854c44ca3e15ce2864e32ce273fdb84e0c446059b7004f40`. APK alignment, 64-bit ELF 16 KiB load alignment, ABI contents and packaged JNI/cartography/resource contracts pass.

Signed RC5 (code 6) retained a 31.2 km journey across force-stop/restart; the production-signed RC6 update (code 7) retained and reopened it. Switching 2D to 3D kept the paused 3.5 km position. Creating the 835.5 km Boston/Niagara sample then completed with the 192 MiB heap limit, and both journeys survived another process restart. The final configuration-only APK was installed over that RC6 build and again retained both journeys after restart; its AndroidRuntime error log was empty.

Manual map bounds passed at 1080×2400 / 420 dpi and 2560×1600 / 240 dpi: [phone 2D](verification-3d/rc6-signed-2d-coast.png), [tablet 2D](verification-3d/rc6-signed-2d-tablet.png). The final APK kept both 3D and 2D mode, Play/paused state and exactly 3.8 km while changing between those sizes/densities and 100%/150% font scale. The 3D surface stayed visible: [restored phone](verification-3d/rc6-signed-density-restored.png). An initial DPI comparison was rejected because the first pause tap had not registered; the accepted run explicitly sought to a paused position and verified the Play control before changing configuration. Normal 3D rotation also retained a visible map. These are software-emulator functional checks, not physical-device frame-rate tests.

Historical RC5 buffering behavior below is superseded by RC6.

CI audit: a prior map-only revision passed GitHub validation, but runs `35092295981` and `35098892964` reached their 30/45-minute limits while release R8 optimization and JVM tests were running together, with no assertion/build error reported before cancellation. The workflow now runs tests/debug/lint before release optimization, with one Gradle worker and in-process Kotlin compilation to avoid overlapping those memory-heavy stages. That revised remote workflow must not be described as passed until its run completes; the final signed APK and all acceptance results above were built and checked locally.

## RC5 adaptive journey update

Implemented on `codex/adaptive-journey`:

- At continental scale, draw the globe without the near-coplanar local map mesh. This removes distant-flight depth-buffer checkerboards and stops irrelevant street requests in globe overview. Near views retain the regional/street map.
- Deterministic north-up camera based on route extent and screen aspect, wider context and longer story time for long movements, overview transitions, smaller playful vehicles and constant-pixel route strokes. Manual scale choices removed.
- Visual terrain skirts blend uncovered DEM edges back to the reference globe; these are coverage transitions, not measured terrain or edited GPS elevations. Map detail zoom uses actual display resolution even when thermal adaptation reduces internal 3D resolution. North-up reduces atlas padding to avoid wasting texture pixels; road-detail selection uses the available 24-tile budget instead of intentionally magnifying coarse tiles.
- Display-only dashed gap connectors with UNKNOWN transport and zero recorded distance. Recorded totals stay authoritative; valid horizontal paths survive missing elevation. Time-proven isolated GPS impulses are rejected only in display geometry, preserving untimed out-and-back tracks.
- Current-viewport map requests prioritize the center and cancel stale requests. Previously cached parent tiles fill detail holes. A shared playback buffer gate pauses movement/photos/music together, waits at most 8 seconds, and has a 20-second retry cooldown. Public OSM route prefetch remains disabled.
- Export freezes existing street PNGs in an isolated, bounded snapshot before encoding. It never fetches a full route from public OSM. This does not provide detailed cartography in previously unseen areas.
- Calendar range selection with UTC date conversion. Import progress reports completed work, stage-weighted percentage and a broad ETA; no timer-driven fake progress. Cancellation propagates.
- Local thumbnail analysis using bundled ML Kit labels, difference hashes, sharpness/exposure and color distributions. Versioned per-media feature cache; one inference at a time. Unknown image content is not treated as a duplicate. Manual representatives survive automatic photo budgets. Card/story quality scoring is shared; day/place coverage and broad content diversity remain explicit selection signals.
- Compact stacked / wide side-by-side diary layout retains composition through window resizing. Main/player activities handle size/orientation configuration changes. Video output aspect is selected separately from device orientation. The in-app player fits the encoded aspect, preserves its instance across rotation, pauses in the background and restores saved position after recreation.
- Portrait and landscape H.264 output at 720p/1080p, same-orientation codec fallback, fitted photo/title overlays, encoder progress/ETA.

### RC5 verification

- 310 JVM tests passed; debug/Android-test builds and lint passed. Lint has 0 errors, 86 warnings and 7 informational findings (including existing project findings).
- Android API 36 functional checks cover the calendar, actual 1280×720 H.264/AAC output, bundled local image labeling and descriptor-cache reuse, and in-app player rotation. Paused playback retained its player instance, position and 16:9 letterboxing.
- The existing five audio/no-audio export, cancellation, gallery-save and share-intent checks passed. Canceled/completed exports left no temporary map-snapshot directories. Both portrait and landscape output files decoded completely with FFmpeg.
- Manual 720×1600 / 320 dpi / 150% font and 2560×1600 / 240 dpi screens verified stacked and side-by-side layouts. A paused map seek stayed at 18.7 km when resized from portrait to landscape, with the Play state preserved. These are emulator viewport checks, not hardware performance benchmarks.
- Production APKs: ARM64 55,836,415 bytes, SHA-256 `c89f422752d66d3263488cf9304b5f8a0441053ce9d5416a82682bf242b2833e`; universal 89,349,625 bytes, SHA-256 `6e2b883f43fadaa25e24f278bf946ff0ebfc42169905d9302982cb5c20a2a3b4`. Both use version code 6 and the original production certificate. Packaged JNI/resource/cartography contracts, signature verification, 16 KiB ZIP alignment and 64-bit ELF load-segment alignment passed. The exact universal production APK installed directly over signed RC4 (code 5 → 6), retained its 31.2 km journey, reopened the mapped playback, and retained the journey again after a forced app process stop/restart. An earlier cold-emulator restart did not retain its just-created fixture, so this signed-update result specifically covers the verified pre-existing fixture and process restart, not power-loss durability. All 16 distinct Android checks passed. The final four affected vehicle/street checks were rerun after the globe correction and passed, including 33 toy poses and the added bright-checkerboard pixel regression for continental/dateline flights. Vehicle pixel checks use an isolated empty street cache so colored real-world map tiles cannot be mistaken for toy paint. Physical S23 Ultra / tablet performance remains unmeasured. The existing original 2D checkout and repository are untouched.

## RC4 north-up camera and street detail

RC3's heading-following camera rotated the entire map, and Natural Earth alone could not identify local roads or neighborhoods. RC4 fixes north at the top and follows only the current location. Vehicles independently face their route tangent. Ground camera distance no longer inherits the timeline arrival zoom; Close / Local / Area / Region scale choices persist and also apply to exports. Flight scale remains wider to show the aerial journey.

OpenStreetMap street PNGs are draped onto the existing terrain atlas. Requests cover only the current interactive screen; there is no route prefetch, headless download or offline pack. One worker per scene uses a stable app User-Agent, HTTP expiry/validators, timeouts, bounded responses, rate-limit backoff and a 96 MiB disk cache. Closing/backgrounding the scene or disabling internet maps stops network requests. The render atlas remains 2048×2048; the tile bitmap cache has at most 48 entries. Internet maps can use mobile data and can be disabled in the terrain dialog. The bundled regional map remains the fallback.

Exports read already cached tiles only and never initiate street-map downloads. Previously unseen areas in a video may therefore use the simpler regional map. On-map and exported attribution credits OpenStreetMap contributors and Natural Earth. More detailed cartography does not alter recorded GPS positions, snap routes to roads, or improve uncertain GPS/DEM measurements. The illustrative Canyon path is still not road matched.

### RC4 verification

- 302 JVM tests passed. Debug and optimized release builds passed. Lint: 0 errors, 86 warnings and 6 informational findings (the added warning suggests using the SharedPreferences KTX helper).
- Production APK: 43,817,765 bytes; SHA-256 `d93554a12fe9563604006eada1ec8f1c4faf8da11881a326d848cbf39ec8af46`. Version code 5, same production certificate as RC1/RC2/RC3. Packaged JNI/resource/map contracts passed.
- Automated map tests use local synthetic tile fixtures or a fake HTTP connection; they do not download from the public tile service. A manual app viewport confirmed that actual OSM roads and place names render over the Canyon terrain.
- The first full-screen smoke test used a regional-view color-count threshold that rejected the new closer rural view. Its saved image showed rendered terrain and the toy car. The check now requires terrain color variation plus red/yellow toy pixels instead of an arbitrary large palette; obscuring system dialogs still fail the test.
- All nine Android checks passed: three street-map/cache checks, two reference-cartography checks, three app/persistence/video checks, and 33 vehicle poses within the all-mode check. Heading reversal and the old arrival zoom leave map pixels unchanged outside the vehicle. The final generated MP4 decoded completely with FFmpeg.
- Installed production-signed RC4 over RC3 without uninstalling in the same emulator session. The saved 31.2 km Canyon journey survived force-stop/restart before the update, stayed in the home list after the update, reopened with visits/movement, and displayed live street detail. The installed package reported version code 5 / 1.0.0-rc4.
- Subsequent small changes collapse the loaded-map credit/status to one line and record the tile revision before texture composition, so a tile arriving during upload is not accidentally marked as already painted. Functional tests above precede these two finishing changes; the final signed APK was reinstalled, reopened the retained journey after emulator restart, and showed a complete street map while paused with the compact credit. [Final signed screenshot](verification-3d/rc4-signed-street.png).
- As with RC3, an external System UI non-response dialog appeared at emulator cold boot and was dismissed before tests. The clean final tests passed; no S23 Ultra hardware/thermal benchmark is claimed.

## RC3 playful vehicles and flight correction

The reported long flight could interpolate a straight chord inside the globe, and the previous aircraft was a nearly flat, very small triangle. Flights now sample the great-circle route and interpolate altitude independently of latitude/longitude. The vehicle nose follows the local route tangent even with a north-up camera. Altitude records and persisted journey geometry are unchanged.

All nine recognized travel modes now have separate colorful solid toy forms (plus an unknown-mode pin). Size follows viewport projection instead of an arbitrarily tiny geographic size. Wheels, walking/running legs and pedaling animate; cars exaggerate measured uphill/downhill pitch, aircraft bank and bob, and boats rock. The animation is a presentation effect, deterministic from story time, shared by playback and exported video. Oversized markers retain their own depth but remain visible over terrain. The three-quarter chase camera exposes the sides and wheels; its local map window is enlarged to cover the more oblique view without increasing the texture dimensions.

### RC3 verification

- 300 JVM tests passed, including sparse continental and dateline flights above Earth, forward-facing direction, inside-wing banking, deterministic seeking, uphill/downhill pitch and bounded models for every mode.
- All six Android checks passed again after the final map-window change: 33 isolated GLES vehicle poses across every mode (continental/dateline flights and north-up views included), the two cartography tests, and three app/playback/export/persistence checks. The final generated MP4 decoded completely with FFmpeg.
- Optimized APK: 43,817,765 bytes; SHA-256 `c5fbd9ee84e9c2825e830b00eaeff661cd56845598e84c82e06e44c363b9b67a`. Production certificate matches RC1/RC2; version code 4. Packaged JNI/resource/cartography checks passed. Lint reports 0 errors and 85 warnings.
- Installed RC3 over production-signed RC2 in the same emulator session: a saved 31.2 km fixture remained in the home list and reopened with its visits/movement. The fixture was also checked after force-stopping/relaunching RC2 before upgrading. A prior attempt spanning an emulator shutdown did not retain the seed fixture and is excluded from upgrade evidence.
- Clean signed RC3 playback shows the enlarged car over mapped relief: [signed app](verification-3d/rc3-signed-car.png). Other actual GLES captures: [uphill](verification-3d/rc3-car-climb.png), [downhill](verification-3d/rc3-car-descent.png), [aircraft](verification-3d/rc3-airplane.png).
- The emulator occasionally showed external System UI non-response dialogs after cold boot. Those captures are excluded; the app tests now reject a capture if such a dialog is present. Final checks passed after dismissing the external dialog. No physical S23 Ultra performance or thermal benchmark was performed.

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
- Vehicle headings follow a sampled route tangent; the camera is north-up. Camera scale/position transitions for extreme gaps and switches between ground and flight remain potential improvements; rewind is deterministic but is not universally cinematic.
- No road matching, detailed buildings, measured bridge deck/tunnel altitude, global terrain package, provider uptime guarantee, or photo migration across phones is claimed.
- Full power-loss/storage-exhaustion/permission-revocation/background stress across supported Android releases remains acceptance work. Backup does not include image files, permission grants, terrain or original import files.

These distinctions remain visible in release notes. RC1 is shipped to enable real use and phone acceptance without calling unverified criteria complete.
