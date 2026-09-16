# Privacy and data handling — 3D edition

Timeline JSON, photo metadata, trip editing, playback and video encoding run on the phone. No account, analytics or photo upload service is added by this edition.

Terrain preparation downloads public elevation tiles from `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/`. That provider receives the requested tile coordinates and your IP address; these can indicate the approximate journey area. Downloads start automatically on unmetered connections when a journey is opened. Mobile-data use requires the terrain dialog option. Pause stops the current journey’s background download. Previously saved terrain remains available without a connection.

Street detail requests visible-area PNG tiles from `https://tile.openstreetmap.org/`. OpenStreetMap receives your IP address and tile coordinates, which reveal the approximate area currently viewed. Internet street detail is enabled by default on Wi-Fi and mobile data, and can be disabled in **3D • Terrain**. Requests stop when the scene is paused or closed. There is no route prefetch, background map pack or export-time download. The separate street cache is limited to 96 MiB and follows HTTP expiry/validation headers. Cached map detail may be evicted; unseen or missing areas use the bundled regional map. Source attribution remains on the map and exported video. See the [tile policy](https://operations.osmfoundation.org/policies/tiles/) and [OpenStreetMap copyright](https://www.openstreetmap.org/copyright).

Terrain cache is separate from trips and original gallery files. Clearing temporary terrain never deletes either. Pinned regions remain until unpinned and cleared. Terrain source credits and modifications are available offline in the terrain dialog.

Journey backup files contain private location history, place labels and local photo references. Store/share them deliberately. They do not include original photos, terrain cache, or operating-system photo permissions. Restoring on the same phone after granting gallery access can reconnect those references; it is not a cross-device photo migration tool. Restoring creates a new journey without replacing an existing one.

The export privacy option generalizes place labels. It does not hide street-map labels, the map route, image contents or readable text inside photos. Review the exported video before sharing. The app only opens the Android sharesheet; you choose the recipient.

Android backup is disabled for app data. Exports and backups are written only to the destination you choose. Removing the app removes its private data; keep journey backups and original source files before switching signing variants.
