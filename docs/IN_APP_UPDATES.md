# 새 앱의 업데이트

2.0.0 New 앱은 기존 앱 옆에 설치하는 별도 앱입니다. 2D 앱 ID는 `com.traveler.next`, 3D는 `com.traveler.threed.next`이며 각각의 전용 배포 키를 계속 사용합니다. 기존 앱의 서명 키와 호환되지 않습니다.

## 휴대폰에서

- 앱을 실행하거나 백그라운드에서 다시 열 때 자동 확인합니다. 하루 한 번 제한은 없습니다. 확인·다운로드 중이거나 업데이트 창이 이미 열려 있으면 중복 요청하지 않습니다.
- 새 버전이 있으면 현재 화면 위에 업데이트 알림을 표시합니다. 최신 상태나 네트워크 오류는 자동 팝업으로 방해하지 않습니다.
- 첫 화면의 업데이트 아이콘으로 직접 확인할 수도 있습니다. 업데이트 창에서 자동 확인을 끌 수 있습니다.
- 다운로드 버튼을 누른 뒤 파일 검증이 끝나면 설치 버튼을 누릅니다. 필요한 경우 ‘이 출처의 앱 설치 허용’ 설정을 안내하고 Android의 설치 확인 화면으로 이동합니다.
- 2.1.0부터 선택형 Wi-Fi 자동 받기를 지원합니다. 기본값은 꺼짐이며 실행 시 자동 확인을 켠 상태에서 작동합니다. Wi-Fi 연결에 다운로드를 고정하므로 모바일 데이터로 전환하지 않습니다. 설치는 Android 확인 화면에서 사용자가 진행합니다. 취소한 설치는 다시 시도할 수 있으며, 다운로드 완료 캐시가 남아 있으면 재실행 시 복구합니다.
- 같은 New 앱 ID·키를 유지한 후속 업데이트는 해당 New 앱의 여행·사진 보관본을 유지합니다. 원래 앱에는 영향을 주지 않습니다.

## 후속 버전 게시

2D는 `HyeokjaeKwon26/My-Travel-Diary`, 3D는 `HyeokjaeKwon26/My-Travel-Diary-3D` GitHub Releases의 최근 30개 공개 릴리스를 확인합니다. 2.0.0은 정식 버전이므로 정식 릴리스만 확인합니다. 이름에 하이픈이 있는 개발 버전만 prerelease를 포함합니다.

1. 앱 ID를 바꾸지 않고 게시된 최신 버전보다 `versionCode`를 높입니다. 2.1.0의 코드는 2이므로 다음 배포는 예를 들어 코드 3, 이름 2.1.1을 사용합니다.
2. 현재 설정된 전용 production 키로 universal Release APK를 만듭니다. 키를 다시 생성하면 업데이트가 막힙니다.
3. UTF-8 변경 기록과 실제 최종 APK로 아래 도구를 실행합니다.
4. 해당 저장소의 정식 릴리스에 APK와 정확히 **update-next.json**이라는 이름의 파일을 함께 올립니다. 태그는 `v` + APK 버전 이름입니다.

```powershell
python tools/prepare_app_update.py path/to/app.apk --tag v2.1.1 --notes path/to/notes.txt --sdk "$env:ANDROID_HOME" --output artifacts/update/update-next.json
```

`update-next.json`은 원래 앱의 `update.json` 채널과 분리됩니다. 도구는 Debug APK·잘못된 앱 ID·이 New 앱의 고정 인증서와 다른 서명·universal이 아닌 APK를 거부합니다. 실제 APK의 버전·크기·SHA-256을 읽어 생성하며, 게시하지는 않습니다. 최신 2.1.0 산출물과 게시 여부는 `artifacts/next-release-2.1.0`의 검증 기록을 확인하세요. 이후 새 버전을 위 방식으로 게시해야 휴대폰에서 새 버전 알림을 받을 수 있습니다.

## 서명 키 보관

이 PC의 빌드는 Git에서 제외된 `keystore.properties`로 전용 키를 읽습니다. 원본 키는 사용자 `.android/traveler-next-signing` 폴더, 별도 사본은 `Documents/Traveler-Signing-Backup` 폴더에 보관합니다. 두 폴더는 현재 사용자와 SYSTEM만 접근하도록 설정했습니다. APK 배포 폴더에는 키와 비밀번호를 넣지 않습니다. 같은 PC의 사본이므로 외장 저장장치 등 안전한 별도 장소에 이 폴더 전체를 추가 보관해야 합니다.

## 검증·개인정보

공식 GitHub 저장소의 HTTPS 주소만 허용합니다. APK 크기·SHA-256·앱 ID·증가한 버전 코드·설치된 앱과 같은 서명·최소 Android 버전·CPU 호환성을 검사하고 설치 직전에 재검사합니다. 여행·사진·위치·개인 식별자는 업데이트 요청에 첨부하지 않습니다. GitHub에는 IP 주소 등 일반 HTTP 연결 정보가 전달됩니다.

테스트 APK는 에뮬레이터에서만 사용하는 debug 키로 생성합니다. 실제 배포 키를 테스트 파일 생성에 사용하지 않습니다.

```powershell
python tools/prepare_update_test_fixtures.py --sdk "$env:ANDROID_HOME" --serial emulator-5580 --application-id com.traveler.next
# 3D: --application-id com.traveler.threed.next
```

[안전한 이전 방법](SIDE_BY_SIDE_MIGRATION.md) · [2.1.0 검증](RELEASE_2_1_VERIFICATION.md).
