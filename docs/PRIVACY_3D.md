# Privacy and data handling — 3D edition

> Current source update / 현재 소스 변경: optional app-owned media copies and photo-inclusive ZIP backups are now supported. Statements below about backups without photos refer to the legacy JSON format and previously published APKs. Copies and ZIPs contain original media and metadata; ZIPs are not encrypted. 휴지통에서는 보관본을 유지하며 여행을 영구 삭제하거나 앱을 제거하면 앱 내부 보관본도 삭제됩니다. [Details / 상세 안내](PORTABLE_MEMORIES.md)

Timeline JSON, photo metadata, trip editing, playback and video encoding run on the phone. No account or photo upload service is added by this edition. Photo content is analyzed locally using the bundled ML Kit image-labeling model. Google documents SDK collection of device/app information and performance/usage diagnostics; this is distinct from uploading photo contents. See [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

Terrain preparation downloads public elevation tiles from `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/`. That provider receives the requested tile coordinates and your IP address; these can indicate the approximate journey area. Downloads start automatically on unmetered connections when a journey is opened. Mobile-data use requires the terrain dialog option. Pause stops the current journey’s background download. Previously saved terrain remains available without a connection.

Street detail requests visible-area PNG tiles from `https://tile.openstreetmap.org/`. OpenStreetMap receives your IP address and tile coordinates, which reveal the approximate area currently viewed. Internet street detail is enabled by default on Wi-Fi and mobile data, and can be disabled in **Map settings**, opened with the gear beside the journey title. Requests stop when the scene is paused or closed. There is no route prefetch, background map pack or export-time download. The separate street cache is limited to 96 MiB and follows HTTP expiry/validation headers. Cached map detail may be evicted; unseen or missing areas use the bundled regional map. Source attribution remains on the map and exported video. See the [tile policy](https://operations.osmfoundation.org/policies/tiles/) and [OpenStreetMap copyright](https://www.openstreetmap.org/copyright).

Photo feature descriptors (visual similarity, quality signals, broad scene labels) are stored in a bounded private cache, keyed by media identity and modification metadata. Deleting this cache triggers local analysis again; it never deletes original photos. Export preparation temporarily freezes up to 96 MiB of existing street tiles, without downloading new areas, and removes that snapshot after encoding.

RC11 also saves each journey's photo selections and analysis in private app data so reopening a journey can reuse them. These saved choices are not copies of the original images: deleting gallery originals makes those photos unavailable to the journey. This local selection snapshot is not included in journey backups; a restored journey prepares its selections again. A video already saved to the gallery retains its encoded photo frames independently of the originals.

Terrain cache is separate from trips and original gallery files. Clearing temporary terrain never deletes either. Pinned regions remain until unpinned and cleared. Terrain source credits and modifications are available offline in the terrain dialog.

Journey backup files contain private location history, place labels, local photo references and any derived photo descriptors present in the opened journey. Store/share them deliberately. They do not include original photos, terrain cache, or operating-system photo permissions. Restoring on the same phone after granting gallery access can reconnect those references; it is not a cross-device photo migration tool. Restoring creates a new journey without replacing an existing one.

The export privacy option generalizes place labels. It does not hide street-map labels, the map route, image contents or readable text inside photos. Review the exported video before sharing. The app only opens the Android sharesheet; you choose the recipient.

Android backup is disabled for app data. Exports and backups are written only to the destination you choose. Removing the app removes its private data; keep journey backups and original source files before uninstalling the app.

## 앱 내 업데이트 (New 2.1.0)

앱 실행 또는 백그라운드에서 다시 열 때 기본으로 공식 GitHub의 공개 배포 정보를 확인하며, 업데이트 창에서 자동 확인을 끌 수 있습니다. GitHub에는 HTTP 연결에 필요한 IP 주소 등의 일반 연결 정보가 전달됩니다. 여행 기록·위치·사진·개인 식별자는 요청에 첨부하지 않습니다. APK는 사용자가 다운로드를 누르거나 선택형 Wi-Fi 자동 받기를 켰을 때 받아 앱의 캐시에 보관하며, 설치는 Android 확인 화면에서 승인해야 합니다. [동작과 배포 조건](IN_APP_UPDATES.md)
