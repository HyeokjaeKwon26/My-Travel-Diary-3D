# 3D alpha implementation status

Date: 2026-09-15. Version: 0.1.0-alpha. Original baseline: `9bcfb4fd81281f54d85cfb1173897d391508c59e`.

This project keeps the original Git history and uses a separate repository and Android application ID. No existing original-app database is migrated in place; the new app owns its own storage.

## Implemented

- ES 2.0 globe/terrain rendering shared between GLSurfaceView and the encoder EGL surface; bounded regional grids, simple vehicles, deterministic journey-following or steady-north camera, 2D fallback.
- A real 129×129 Grand Canyon elevation grid (approximately 699–2603 m), development-side pack builder with source tile hashes, validated offline import (4 MB, 257×257, three imported regions max).
- Shared source path selection; removed renderer-only endpoint snapping and speculative connectors. Removed story gap bridges and manufactured 10,500 m cruise elevations. Real recorded return routes remain in the data.
- Current movement emphasis, earlier routes faded and future routes hidden during 3D playback. Vehicle position comes from the rendered 3D polyline.
- Isolated altitude spike rejection and conservative steep-discontinuity suppression; no coordinate relocation toward a guessed road. Surface heights do not overwrite recorded data.
- Room schema 6 preserves endpoint/visit altitude and timed raw points. Representative-photo override restoration is applied during re-import.
- H.264 1080×1920/30fps export using the same GPU scene; Canvas remains for transparent photo/title overlays. Audio uses one bounded PCM allocation; photo cache is capped at 24 MB.
- Export exception/cancellation handling, legacy Android storage permission, and conservative private-place name generalization.
- A clearly labelled illustrative Grand Canyon journey can be created without providing personal data.

## Verification

Final verification results are recorded below after the local test run completes. The test device is an Android 16 / API 36 `medium_phone` emulator using software graphics. Its timings must not be presented as ordinary-phone performance.

## Remaining work and known limits

- Reproduce the user's original two-line issue with a screenshot and the relevant trip data. Synthetic tests and removal of code-level causes do not prove the reported case is fixed.
- Measure startup, frame-time p95, memory and thermal behavior on actual phones; implement adaptive quality and complete sustained playback/seek stress tests.
- Improve camera transitions between distant episodes, automatic terrain visibility selection, terrain-edge blending and high-resolution route draping. DEM simplification can still place a displayed path imperfectly on a steep face.
- Better uncertainty classification needs original accuracy/time/provenance and possibly road data. Current spike/slope thresholds are heuristics, not a general GPS correction system. Bridges and tunnels remain unresolved.
- Region import loads bounded whole grids rather than a paged multi-resolution terrain cache. Raw timed coordinates are stored as JSON, not a compressed bounded archive.
- MP4 currently offers fixed 1080p output; 720p selection and broader codec/device fallback remain future work. Final Android 8–9 save behavior needs device verification.
- World flight arcs without observations are visual only. Detailed buildings/satellite imagery and network downloads are outside this alpha.
- Privacy filtering does not hide the route or redact text within photos. Review exports before sharing them.

The original roadmap is retained as a plan, not a checklist claimed fully completed by this alpha.
