# 시험장 무선 신호 관측기 / Exam Signal Watch

**Android / Kotlin / 완전 무료 / 오프라인 / 실험용 v0.1.0**

시험 감독관이 무선 신호를 참고할 수 있도록 주변 **BLE 광고 신호**와 **와이파이 접속점(AP)**을 관측하고 기록하는 프로젝트입니다. 신호 관측과 수험생의 부정행위 판단을 엄격히 구분합니다.

> ⚠️ **현재 APK는 포함되어 있지 않습니다.** 이 저장소에는 Android Studio 프로젝트의 소스 코드와 GitHub Actions 빌드 설정이 들어 있습니다. 현재 개발 환경에서 Android SDK 및 실제 기기에 접근할 수 없어 APK 빌드와 실기기 테스트는 완료되지 않았습니다. `v0.1.0`을 공식 감독 장비로 소개하지 마세요.

## 구현된 소스 범위

| 기능 | 코드 상태 | 실기기 검증 |
|---|---|---|
| BLE 광고 신호 검색 | 구현 | 미검증 |
| 와이파이 접속점 검색 (Android 탐색 제한 준수) | 구현 | 미검증 |
| 최초 20초 기준 신호 기록 | 구현 | 미검증 |
| 검사 시작·종료 / 최근 관측 시각 표시 | 구현 | 미검증 |
| 관측 내역 로컬 SQLite 저장 | 구현 | 미검증 |
| CSV 저장 / 전체 기록 삭제 | 구현 | 미검증 |
| 원시 MAC 주소 대신 세션별 해시 ID 저장 | 구현 | 미검증 |
| RTL2832 USB 수신기 연결 *추정* 상태 표시 | 구현 | 미검증 |
| **RTL-SDR RF 신호 수신·주파수 탐색** | **미구현** | — |
| 클래식 블루투스 Inquiry/전체 통신 감시 | 미구현 | — |
| 수신 전용 이어폰 탐지 | 지원하지 않음 | — |

## 안전·운영 특성

- Manifest에 `INTERNET` 권한이 없습니다. 서버 전송, 광고, 추적 SDK가 없습니다.
- 인근 기기와 연결하거나 통신 내용을 읽지 않습니다.
- 기록은 기기 내부에만 저장됩니다. CSV 내보내기는 사용자가 직접 선택합니다.
- 인근 접속점은 교실 밖에서 전송될 수 있습니다. 신호 수는 실제 기기 수가 아닙니다.
- Android BLE 검색은 모든 블루투스 기기를 보장하지 않으며, Wi-Fi 검색도 AP 중심이고 OS 빈도 제한을 받습니다.
- 시험 감독관 기기 사용은 반드시 해당 시험의 공식 규정과 승인을 따릅니다.

## Android Studio로 열기

1. Android Studio의 최신 안정 버전에서 이 폴더를 **Open**합니다.
2. Android SDK Platform 35와 Java 17을 준비합니다.
3. Gradle 8.11.1 이상 호환 환경과 Gradle Android Plugin 8.9.2 / Kotlin 2.1.10 의존성을 내려받아 Gradle 동기화를 완료합니다.
4. 실제 Android 8.0 이상 스마트폰을 연결해 `app`을 실행합니다.
5. `근처 기기` 및 `정확한 위치` 권한을 허용하고, Wi-Fi 검색 시 시스템 위치 서비스를 켭니다.

이 프로젝트에는 Gradle Wrapper 실행 JAR가 포함되지 않았습니다. 로컬에 Gradle 8.11.1을 설치한 경우 프로젝트 최상위에서 `gradle :app:assembleDebug`를 실행할 수 있습니다. GitHub Actions에서 Gradle과 SDK를 자동으로 설치하는 경로도 제공합니다.

## GitHub Actions로 디버그 APK 빌드

GitHub 저장소에 전체 폴더를 올리고 **Actions → Android Debug APK → Run workflow**를 실행하세요. `.github/workflows/android-debug.yml`이 빌드를 시도하며, 성공한 경우 실행 결과에서 `ExamSignalWatch-debug-apk` 아티팩트를 내려받을 수 있습니다.

> 최초 빌드가 실패하면 Actions 로그를 확인하세요. 아직 실제 Android SDK 환경에서 실행해 보지 않은 초안이므로 성공을 보장하지 않습니다.

이 디버그 APK는 시험장에 바로 배포하기 위한 최종판이 아닙니다. 배포 전 앱 ID, 서명 키, 허가·호환성·개인정보 검증 및 실제 기기 테스트를 진행해야 합니다.

## 기능·검사 과정

1. BLE 및 Wi-Fi가 켜져 있는지 확인하고 검사 시작
2. 20초 동안 주변 기준 신호 수집
3. 이후 처음 관측되는 신호를 `신규`로 표시
4. 최근 신호 및 관측 갱신 시각 확인
5. 검사 종료 후 SQLite 기록을 CSV로 내보내기

화면 이탈 시에는 신호 탐색이 중단되고 검사 기록을 종료합니다. 백그라운드 감시를 지원하지 않습니다.

## 소스 및 기술 참고

본 소스는 Android 표준 API를 사용해 **독립적으로 작성한 구현**입니다. 외부 프로젝트의 구현 코드를 복사하거나 합치지 않았습니다. 기존 프로젝트의 기능과 설계 방향을 참고했으며 실제 통합은 향후 별도 작업입니다.

- [RF Sentinel](https://github.com/CIS-C0/RFSentinel) (GPL-3.0) — BLE/Wi-Fi/RTL-SDR 구현 연구용 참고
- [NoPeek Android](https://github.com/getnopeek/nopeek-android) (MIT) — 스마트안경 탐지 방향 참고
- [Spectrum](https://github.com/hypermagik/Spectrum) — 안드로이드 전파 시각화 방향 참고
- [Android BLE 문서](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner)
- [Android Wi-Fi scanning 문서](https://developer.android.com/develop/connectivity/wifi/wifi-scan)

**라이선스:** 이 저장소에 새로 작성한 소스는 MIT (LICENSE 참고). RF Sentinel 등의 GPL 소스를 이후 복사·통합한다면 라이선스, 원작자 고지 및 소스 배포 조건을 재검토해 지켜야 합니다. 참고 링크는 코드 재사용을 의미하지 않습니다.

## 기기 검증 필요

- Android 8~16의 권한 동작 및 BLE 검색 상태
- 안드로이드 전력 관리 / 화면 꺼짐 시 감시 중지
- Wi-Fi Scan API 제한 발생 시 사용자 상태 표시
- 동일 기기의 무작위 MAC 변경 및 식별자 오인
- 누락/오경보 측정 및 교실 외부 신호 유입
- USB RTL-SDR V4 검출 정확성 (RF 수신은 아직 없음)

## 문서

- 앱 내부 오프라인 사용설명서: `app/src/main/assets/guide_ko.txt`
- 개발 지침과 미구현 내용: `docs/DEVELOPMENT_NOTES_KO.md`
- 현장 실험 체크리스트: `docs/FIELD_TEST_KO.md`

실업급여 등 별도 행정적 요건은 앱의 무료 여부나 배포 경로에 따라 자동 해결되지 않습니다. 정식 대외 배포 전 개인의 상황에 따라 관할기관에 확인하세요.
