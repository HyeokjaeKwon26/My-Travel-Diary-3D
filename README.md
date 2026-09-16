# My Travel Diary 3D

An independent Android 3D edition of [My Travel Diary](https://github.com/HyeokjaeKwon26/My-Travel-Diary), based on original commit `9bcfb4f`.

**Status: 0.1.0-alpha.** This is a working terrain/playback prototype, not a claim of production readiness or measured performance on every phone.

## 한국어

Google Timeline JSON과 휴대폰 사진을 여행 다이어리로 구성하고, 지구본과 간단한 실제 지형 위에서 이동을 3D로 재생하는 Android 앱입니다. 앱의 데이터 처리와 재생은 오프라인으로 동작합니다.

- 앱 이름: **My Travel Diary 3D**
- 별도 앱 ID: `com.traveler.threed` — 기존 앱과 함께 설치할 수 있습니다.
- 기존 앱의 저장소·설치 데이터는 변경하지 않습니다. 기존 여행이 자동 복사되지는 않으므로 Timeline 파일을 새 앱에서 가져와 주세요.
- Android 8.0 이상을 대상으로 빌드합니다. 실제 지원 성능은 기기 검증이 더 필요합니다.

### 이번 버전

- OpenGL ES 2.0 지구본, 지역 지형, 간단한 자동차/비행기와 추적 카메라.
- 실제 공개 고도 데이터로 만든 **Grand Canyon South Rim** 오프라인 지형 팩.
- 지역 지형 팩 가져오기, 고정 북쪽 카메라, 2D 전환.
- 지도와 재생에서 경로 준비를 공유하고, 표시 전용 직선 연결·근거 없는 이동 구간 자동 생성을 제거.
- 재생 중 미래 경로를 숨기고 과거 경로를 흐리게 표시하여 왕복 경로 혼동을 줄임.
- 원래 고도와 시간별 좌표 보존, 단일 고도 이상치 필터, 급격한 고도 불연속 표시.
- 같은 3D 렌더러를 사용하는 H.264 MP4 출력과 선택적 AAC 음악.
- 영상 저장 오류·취소 처리, 사진 캐시 상한, 주소 일반화 옵션 적용.

### 바로 체험하기

1. APK를 설치하고 **+ New Travel Story**를 누릅니다.
2. **Try Grand Canyon 3D • illustrative route**를 누릅니다. 개인 위치 기록 없이 예제를 볼 수 있습니다.
3. 여행 지도에서 재생 버튼을 누르거나 전체 화면으로 엽니다.
4. **3D • Terrain**에서 카메라·2D 전환·지형 팩을 설정합니다.
5. 실제 여행은 새 여행 화면에서 Timeline JSON과 날짜를 선택하여 가져옵니다.

예제 경로는 연출 확인용이며 실제 GPS 기록이나 도로에 맞춘 경로가 아닙니다. 지형은 실제 고도 샘플을 사용합니다.

### 현재 한계

- 지형 팩 범위 밖에는 상세한 산·계곡이 없습니다. 기본 지구본이 표시됩니다.
- 고도가 없는 비행의 상승 곡선은 시각적 연출이며 측정 고도가 아닙니다.
- DEM은 지면 높이입니다. 다리·터널·절벽 가장자리의 GPS 오차를 정확한 도로 높이로 복원하지 않습니다.
- 일반 스마트폰의 30fps, 발열, 10분 지속 재생은 아직 실기기 검증 전입니다.
- 사용자가 보고한 두 줄 현상은 실제 자료를 받지 않아 해당 사례의 해결 여부를 확정하지 않았습니다.
- 자동 성능 조절, 정교한 카메라 전환, 720p 출력 선택, 전 세계 도로 매칭은 후속 작업입니다.
- 주소 일반화는 장소 이름에 대한 보수적인 필터입니다. 사진 속 주소나 지도 경로를 익명화하지 않습니다.

상세한 구현 범위와 검증 기록: [구현 상태](docs/IMPLEMENTATION_STATUS.md) · [개발 계획](docs/3D_TRAVEL_ROADMAP.md) · [지형 팩 형식](docs/TERRAIN_PACKS.md).

## Preview

Actual emulator GLES frame (illustrative path over real terrain):

<img src="docs/verification-3d/canyon-3d.png" width="320" alt="3D vehicle following a route across simplified Grand Canyon terrain" />

## Build and validation

Requires JDK 17 and Android SDK platform 36. Open in Android Studio or run:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
# With an Android emulator/device connected:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.traveler.feature.map.ThreeDIntegrationTest,com.traveler.core.database.RoomMigrationAndroidTest,com.traveler.feature.video.TravelVideoExportAndroidTest
```

On Windows use `gradlew.bat`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions builds an APK and uploads the unit-test/lint reports for pushes and pull requests.

The instrumentation suite checks an actual GLES frame, exports and decodes an MP4, tests audio/cancellation/gallery storage, and verifies Room migration and altitude persistence. It is not a physical-device performance benchmark.

## Data and license

AGPL-3.0; original attribution/history retained. Natural Earth provides the public-domain world polygons. The bundled US terrain is derived from Mapzen Terrain Tiles / USGS sources; see [terrain attribution and source manifests](docs/TERRAIN_PACKS.md). Building new terrain packs downloads public data on the development computer. The Android app does not request INTERNET permission.

Historical documents and screenshots inherited from the original project describe the original 2D edition; they are not evidence of this alpha's features or performance.
