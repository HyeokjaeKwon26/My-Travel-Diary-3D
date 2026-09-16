# Traveler - 오프라인 벡터 베이스맵 출처 및 생성 사양 (Basemap Sources & Specification)

본 문서는 Traveler 애플리케이션에 내장된 오프라인 벡터 베이스맵의 원천 데이터셋, 라이선스, SHA-256 체크섬 및 생성 파이프라인을 기술합니다.

## 3D 지도 표면 (1.0.0-rc2)

`tools/build_3d_cartography.py`는 기존 1:10m 지역 지도와 Natural Earth 도로·강·도시 영역을 함께 변환합니다. 원천은 `nvkelso/natural-earth-vector`의 고정 커밋이며 URL과 SHA-256은 [cartography-sources.json](verification-3d/cartography-sources.json)에 기록합니다. 라이선스는 [Natural Earth public domain](https://www.naturalearthdata.com/about/terms-of-use/)입니다.

생성물 `app/src/main/assets/basemap_3d.bin.gz`는 미리 투영한 좌표를 저장합니다. AAPT가 gzip을 풀어 APK에는 `assets/basemap_3d.bin`으로 패키징합니다. 앱은 이를 한 번 읽어 메모리에 보관하고, 화면 주변만 최대 2048×2048 텍스처 한 장으로 그립니다. 같은 지도가 3D 지형과 영상에 적용되며 휴대폰에서 별도로 지도 파일을 다운로드하지 않습니다.

자료는 지역 단위 참고 지도이며 모든 골목길이나 최신 도로를 담지 않습니다. 경로를 도로에 맞추는 기능도 아닙니다. 기존 고도 다운로드와 지도 표시는 독립적입니다.

---

## 1. 정책 및 원칙 (Zero-Network Policy)

* **100% 로컬 오프라인**:
  * 런타임에 외부 타일 서버, Mapbox/Google Maps 서버 요청, PMTiles/MBTiles 외부 다운로드를 일체 수행하지 않습니다.
  * 모든 지리 벡터(해안선, 호수, 국경선, 주/도 경계선, 주요 지명)는 APK 내 `assets/basemap_world.json`으로 번들링되어 제공됩니다.
* **검증된 퍼블릭 도메인 데이터셋만 사용**:
  * 전세계 지도 제작 표준 오픈 데이터인 **Natural Earth Vector Suite (1:110m / 1:50m)**의 공식 GeoJSON 소스 파일만을 사용하여 빌드 시점에 생성합니다.
  * 라이선스: **Public Domain (CC0 1.0 Universal)** — 상업적/비상업적 제한 없이 영구적 재배포 가능.

---

## 2. 원천 데이터셋 명세 (`tools/basemap_sources/`)

| 소스 파일명 | 원천 데이터셋 | 데이터 유형 | 용량 (Bytes) | SHA-256 체크섬 |
| :--- | :--- | :--- | :--- | :--- |
| `ne_110m_land.geojson` | Natural Earth 1:110m Land | 전세계 육지 다각형 및 해안선 | 138,160 | `0e386ce0d8b4b73b567d1dbbe68fbbdb56c0733d3c8c73d9e8eb3c56a8dbb83e` |
| `ne_110m_lakes.geojson` | Natural Earth 1:110m Lakes | 오대호, 카스피해, 빅토리아호 등 주요 수계 | 36,648 | `7ff15b6d9be7e4bb004cf8a48ef84cf9dc3175bb876ef48ae6673bfba80bfd03` |
| `ne_110m_admin_0_boundary_lines_land.geojson` | Natural Earth 1:110m Admin-0 | 전세계 국가 간 국경선 | 340,010 | `fefd784d193cf9cf2996e38e658ec3421396a583e7ffb2a0ebc7217e65158656` |
| `ne_110m_admin_1_states_provinces_lines.geojson` | Natural Earth 1:110m Admin-1 | 미국 50개 주, 캐나다 주/도 경계선 | 60,552 | `d12aa52a1ba763158022b78d2b7754b20dd676a6cfb16279f048d08595856fc8` |
| `ne_110m_populated_places_simple.geojson` | Natural Earth 1:110m Populated Places | 전세계 주요 도시 및 지역 거점 | 166,071 | `fe7df77e5e3057e937d10e0513e9a59b665dfd12a6730ef33fe73e35ef414757` |

---

## 3. 베이스맵 생성 및 검증 파이프라인

1. **생성 명령어**:
   ```bash
   python tools/generate_basemap.py
   ```
2. **무결성 검증 명령어**:
   ```bash
   python tools/generate_basemap.py --check
   ```
3. **최종 출력 에셋**:
   * 경로: `app/src/main/assets/basemap_world.json`
   * 용량: 약 461 KB
   * SHA-256: `ed086f9de291c3d45b87126c1d0e266ced2f05f113ce56160e6eb0a20e78c651`
   * 구성: 육지 다각형 (`land`), 주요 호수 (`lakes`), 국가 경계선 (`ADMIN_0`), 주/도 경계선 (`ADMIN_1`), 전세계 주요 도시 (`places`)
