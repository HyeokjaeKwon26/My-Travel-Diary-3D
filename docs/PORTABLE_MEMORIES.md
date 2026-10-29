# Photo storage and portable backups — development update

This feature is in the current source branch. Previously published RC APKs do not include it.

## 사용 방법

여행 일지의 요약 아래 **사진 보관·백업**을 누릅니다.

- **사진·동영상 모두 앱에 보관**: 여행에 연결된 미디어를 앱 내부 저장소에 복사합니다. 완료된 파일은 원본을 삭제하거나 갤러리 권한을 잃어도 사용할 수 있습니다. 원본 파일과 기존 여행 기록은 수정하지 않습니다. 보관 수와 용량을 화면에서 확인할 수 있습니다.
- **사진·동영상 포함 백업**: 여행 기록과 모든 연결 미디어를 `.travel.zip` 파일로 저장합니다. 날짜 미확정 미디어도 포함합니다. 첫 화면 **Restore**에서 두 앱 모두 복원할 수 있습니다. 복원은 새 여행을 만들며 기존 여행을 덮어쓰지 않습니다.
- **여행 기록만 백업 (JSON)**: 기존 `.travel.json` 형식입니다. 미디어 파일은 포함하지 않으므로 사진을 다른 기기로 옮기는 용도로 사용하지 마세요.

보관은 선택 사항입니다. 아직 보관하지 않은 파일은 원본과 권한이 필요합니다. 여행 삭제, 앱 삭제, 앱 데이터 초기화는 앱 내부 보관본도 삭제합니다. 앱 밖에 저장한 사진 포함 백업과 이미 저장한 MP4는 별도로 남습니다. 갤러리에 나중에 추가한 파일은 자동으로 여행에 추가되지 않습니다.

파일당 최대 512 MB, 여행 보관 및 백업 미디어 합계 2 GB를 지원합니다. 백업을 준비하는 동안 비슷한 크기의 임시 공간이 추가로 필요합니다. 읽을 수 없는 파일이 있으면 성공으로 표시하지 않습니다. 취소 시 완료된 보관 파일은 유지하며 다시 실행하면 이어서 처리합니다. 저장 중 취소하거나 저장 제공자에 오류가 생긴 백업 파일은 불완전할 수 있으므로 다시 만드세요.

동영상으로 찍은 추억은 첫 키프레임을 정지 썸네일로 표시합니다. 일지와 지도 재생, 출력 MP4에 적용되며 원본 동영상의 전체 재생이나 음성 삽입 기능은 아닙니다.

## Usage

Open **사진 보관·백업** (Photos & backup) below the trip summary. Keep all linked photos/videos in the app, create a media-inclusive `.travel.zip`, or retain the existing metadata-only `.travel.json` option. The ZIP format works in both updated editions and restores a new trip with local copies, without source-phone permissions. Older APKs cannot restore this new ZIP format.

Storage is optional. Completed copies survive removal of gallery originals, but deleting the trip, clearing app data or uninstalling deletes the app's copies. Export a ZIP outside the app before changing phones or uninstalling. JSON backups still require the original media. Later gallery additions are not automatically imported into an existing journey.

Limits: 512 MB per media file, 2 GB total media and 32 MB metadata. Creating a backup needs temporary disk space. Missing/unreadable media cause an explicit failure. Cancellation retains completed app copies for retry; a destination backup interrupted during writing may be incomplete and should be recreated.

Videos appear as upright, aspect-preserving still frames in the diary, playback and exported MP4. This does not splice video clips or their audio into the story.

## Storage, format and privacy

- No database schema change. Optional copies live in `filesDir/trip_media/<hashed-trip-id>/`; normal journeys retain their original database URIs. Saved photo selections are reused when resolving copies.
- Portable format `MyTravelDiaryMedia`, version 2: a ZIP with bounded `manifest.json` and numbered `media/*.bin` entries. Metadata includes file lengths and SHA-256 digests. Restore checks CRCs, hashes, complete membership, duplicates and entry names before database commit.
- Restore stages files in an isolated directory and namespaces trip/media/visit/segment IDs to avoid collisions with existing trips and manual overrides. Failed imports remove staged files. Database commit and its file ownership transition are protected from coroutine cancellation.
- Streams and bounded bitmap caches avoid reading entire videos/backups into RAM. API 26 video frame extraction uses the platform's unscaled fallback before resizing; later Android versions request scaled frames.
- Copies and ZIPs contain original media bytes, including any embedded EXIF/location metadata. ZIPs are not encrypted. They also contain travel locations and names. Store or share them intentionally; no automatic cloud upload is added.
- Maps, downloaded terrain, original Timeline JSON and Android permission grants are not included in the ZIP.

## Verification

New tests cover original deletion, idempotent storage, cancellation/retry, missing-source failures, backup round trips, repeat restore, tampered/traversal archives, portrait video rotation, Coil loading and video stills in export frames. See [the verification report](RELEASE_2_1_VERIFICATION.md) for executed results and practical limits.
