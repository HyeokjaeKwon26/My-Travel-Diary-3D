# Codex 인수인계 점검 — 2026-09-14

## 기준과 범위

- 사용자 요청: Antigravity에서 진행하던 프로젝트의 목적, 구현 상태, 문제점을 파악하고 이후 개발을 이어받기.
- 저장소: https://github.com/HyeokjaeKwon26/My-Travel-Diary
- 확인한 HEAD: `9bcfb4fd81281f54d85cfb1173897d391508c59e`. 원격 HEAD와 일치하며 점검 시작 시 작업 트리는 깨끗했음.
- 앱 코드는 수정하지 않았음. 이 문서는 현재 코드의 정적 검토와 로컬 검증 기록이며, 실제 사용자 데이터/실기기 재현 결과와 구분함.

## 제품과 구조

Google Timeline JSON과 기기 갤러리의 촬영 시간/GPS를 결합해 여행 다이어리, 지도 애니메이션, 음악이 포함된 MP4를 만드는 Android 앱.

- Kotlin 1.9.22 / Jetpack Compose / Room schema v5, 단일 `app` 모듈.
- minSdk 26, compileSdk/targetSdk 36, 앱 버전 1.0.0.
- 자체 Android Canvas 지도와 Natural Earth 데이터, TimeShape 오프라인 시간대 판별.
- Manifest에 INTERNET 권한이 없고 Android 자동 백업도 비활성화되어 있음.
- `GoogleTimelineJsonParser` → `MovementTimelineCanonicalizer` → `CreateTripUseCase` → 사진 시간/장소 매칭 → `TripRepositoryImpl` / Room 저장.
- `TravelDiaryScreen`에서 방문지·이동 구간·사진 탐색 및 이름/교통수단/대표 사진 수정.
- `PhotoStoryEngine`이 대표 사진을 선정하고 `TravelStoryTimeline`이 재생 시간을 구성. 지도와 MP4가 같은 타임라인 엔진을 사용하되 MP4에는 타이틀/엔드 카드가 추가됨.
- `TravelVideoRenderer` → `TravelVideoEncoder`(H.264 + AAC) → 캐시 MP4 → 갤러리 저장/공유.

## 기존 작업의 주된 난제

문서와 회귀 테스트를 보면 최근까지 아래 문제를 집중적으로 다뤘음. 현재도 사용자 데이터로 재확인할 핵심 영역임.

1. 중복·겹치는 타임라인과 GPS 잡음으로 생기는 가짜 이동/경로 점프.
2. 해외여행, 날짜변경선, DST, EXIF 시간대 누락에 따른 사진 날짜·방문지 오배정.
3. 너무 많은 사진으로 길어지는 재생, 사진 순서 역전, 사진 GPS 때문에 이동 마커가 튀는 현상.
4. 장거리 비행 경로, 지도 확대와 카메라 움직임, 해안선/지역 지도의 표현.
5. MP4의 타임스탬프·음악 트랙·취소 처리와 메모리 사용량.

## 현재 코드에서 확인한 결함

### 우선: 영상의 주소 보호 옵션이 연결되어 있지 않음

- `feature/video/ui/ExportVideoDialog.kt`는 “Generalize Home Location / Hides exact address numbers for privacy” 옵션을 기본 활성화로 표시함.
- `generalizeHomeAddress`는 exporter를 거쳐 renderer 생성자에 전달되지만 `TravelVideoRenderer.kt`에서 한 번도 사용되지 않음.
- 사진 카드의 라벨은 `currentVisit.placeName`을 그대로 사용하며, 지도 재생 배너도 방문지 이름을 사용함.
- 주소가 방문지 이름에 포함되면 옵션을 켜도 영상에 남을 수 있음. 공유 영상의 모든 텍스트 경로에 대한 적용과 ON/OFF 검증이 필요함.

### 우선: 영상 내보내기 실패가 UI 오류 처리로 이어지지 않음

- `ExportVideoDialog.kt`의 제작 버튼은 `rememberCoroutineScope().launch`에서 `exportVideo()`를 try/catch 없이 호출함.
- encoder 및 MP4 검증은 실패 시 예외를 던짐. 현재 오류 화면은 반환값이 null인 경우만 처리하므로 코덱/파일/검증 예외가 앱 종료로 이어질 수 있음.
- 갤러리 저장도 `TravelVideoExporter.saveVideoToMediaStore()`의 `resolver.insert()`가 try 블록 밖에 있어 삽입 실패 예외가 그대로 전파됨.
- 실패 예외와 coroutine 취소를 구분하고, 화면 수명과 파일/코덱 정리를 함께 검증해야 함.

### Android 8/9 갤러리 저장 지원 누락

- minSdk 26이고 API 28 이하용 MediaStore 경로가 존재함.
- Manifest와 권한 요청에는 `WRITE_EXTERNAL_STORAGE`가 없음. 구형 Android의 공용 저장소 쓰기 경로에 필요한 권한 처리가 누락되어 있음.
- 플랫폼 근거: [Android 공유 미디어 저장소 문서](https://developer.android.com/training/data-storage/shared/media).
- 이 경로는 연결 기기가 없어 실기기 재현하지 않았음. API 26/28에서 저장 및 권한 거절을 검증할 것.

### 대표 사진 설정의 재가져오기 복원 누락

- `TripRepositoryImpl.setRepresentativeMedia()`는 `MEDIA_REPRESENTATIVE`를 durable override 테이블에 저장함.
- `CreateTripUseCase`의 재가져오기에서는 `VISIT_NAME`, `SEGMENT_TRANSPORT`만 적용하고 `MEDIA_REPRESENTATIVE`는 읽지 않음.
- 현재 여행 내 수정은 저장되지만 같은 데이터를 새 여행으로 가져올 때 대표 사진 선택은 복원되지 않는 구조임.

## 아직 재현하지 않은 위험과 유지보수 문제

- **메모리:** 과거 검증 기록에서 TimeShape만으로 Java heap 약 179MB / 192MB를 사용했음. 현재 실측은 아님. 영상 renderer는 표시한 모든 사진 bitmap을 종료 시까지 보관하며, encoder는 약 31.3MB WAV를 여러 byte 배열로 복사함. 긴 여행/큰 사진/저메모리 기기 검증 우선.
- **취소와 화면 회전:** encoder 프레임 루프는 atomic flag만 검사하고 coroutine 취소를 직접 확인하지 않음. 버튼은 flag와 job을 모두 취소하지만 화면 제거 시 같은 정리 흐름이 보장되는지 확인 필요.
- **동영상 원본:** 갤러리 스캐너와 대표 미디어 선정은 video를 허용하지만 MP4 카드 decoder는 `BitmapFactory.decodeStream()`만 사용함. 동영상이 대표로 선택되었을 때 썸네일이 비는 경로를 검증할 것.
- **대용량 처리:** parser는 각 activity마다 전체 관측 지점 목록을 filter함. 긴 기간의 자료에서는 activity 수 × 지점 수 비용이 발생할 수 있음.
- **문서/검증 불일치:** `AI_HANDOFF.md`, `PROJECT_STATE.md`는 Pass 21.1과 247/13 테스트를 기재하고, `verification/BUILD_METADATA.txt`는 Pass 21과 252/8, 별도 배포 JSON은 Pass 21.7을 가리킴. README의 SDK 34 안내도 실제 compileSdk 36과 다름.
- **검증 공백:** 공개 GitHub에 CI workflow가 없으며, 과거 화면 캡처와 검증 파일만으로 현재 기능의 성공을 단정할 수 없음.
- **코드 집중:** timeline, diary UI, parser, map renderer가 각각 수백~천 줄 규모임. 기능 수정 시 해당 회귀 테스트부터 확인하고 필요한 부분부터 분리하는 편이 적절함.

## 후속 작업 순서 제안

1. 영상 주소 보호 옵션, 제작/저장 예외 처리, 구형 Android 저장 권한을 함께 점검.
2. 사용자가 실제 겪은 사진·경로·영상 문제를 실제 기기에서 재현하여 회귀 사례로 고정.
3. 가져오기/영상 제작 메모리 및 긴 여행 성능 측정.
4. 대표 사진 재가져오기 복원과 취소/화면 회전 동작 정리.
5. 현행 버전에 맞춘 인수인계 문서와 자동 검증 경로 정비.

## 이번 로컬 검증

- JDK 17과 Android SDK 36이 로컬에 설치되어 있음. 시스템 PATH의 Java는 별도 구버전이라 검증 명령에만 JDK/SDK 환경 변수를 지정함.
- `testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL. 테스트와 APK는 Gradle의 UP-TO-DATE 결과를 재사용했고 lint는 새로 실행했음.
- 현재 lint: 오류 0, 경고 80, 정보 3. 주로 KTX 사용 권고와 의존성 업데이트 안내이며 이 수치 자체가 기능 오류 수는 아님.
- 연결된 Android 기기/실행 중 에뮬레이터가 없어 계측 테스트와 실제 영상 재생은 이번에 수행하지 않았음.
- 저장소에 포함된 기존 스크린샷은 참고용으로 확인했으며 새로 촬영한 증거가 아님.
- JVM 테스트는 캐시와 구분하기 위해 `:app:testDebugUnitTest --rerun`으로 추가 실행함. 36초 후 BUILD SUCCESSFUL, 277개 테스트 / 실패 0 / 오류 0 / 건너뜀 0. XML 결과의 갱신 시각도 확인함.
- 이번 변경은 이 점검 문서 추가뿐이며 commit/push는 하지 않았음.
