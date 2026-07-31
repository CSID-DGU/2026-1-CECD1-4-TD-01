# 2026-07-29 보호자 모드·위험 알림·IoT 제어

## Android v2.0.0

- 첫 실행에서 사용자 또는 보호자 역할을 선택하고 앱 설정에 저장한다.
- 역할은 설정에서 바꿀 수 있다.
- 어느 역할이든 버전 항목을 5회 누르면 개발자 화면으로 전환된다.
- 사용자 화면은 기존 대화·Gallery·건강·생활 패턴을 유지하며 IoT 탭을 추가했다.
- 보호자 화면은 안심 알림과 IoT 제어에 집중한다.
- 보호자 역할에서는 연결된 기기 foreground service가 Jetson 알림을 15초마다 확인한다.
- 새 위험 신호는 Android 고우선순위 알림과 보호자 안심 화면에 표시된다.
- 보호자는 알림을 확인 처리할 수 있다.
- 보호자 Jetson 설정 화면은 연결 정보만 저장하며 휴대폰 건강·Gallery·생활 자료를 전송하지 않는다.
- IoT 화면은 Jetson이 공개한 허용 장치와 허용 동작만 표시한다.

## Jetson

- `guardian-alert-v1` 입력을 검증하고 SQLite `guardian_alerts` 테이블에 저장한다.
- 알림 목록은 증가하는 sequence cursor로 중복 없이 조회한다.
- 위험 알림 조회와 확인 처리 API를 추가했다.
- Home Assistant 제어는 `iot_devices.json`의 허용 목록만 사용한다.
- Home Assistant 토큰과 `entity_id`는 Android 응답에 포함하지 않는다.
- `light`, `switch`, `fan`, `lock`, `cover`의 제한된 동작만 허용한다.
- Home Assistant 예제는 승인되지 않은 RFID 출입을 식별정보 없는 보호자 알림으로 전달한다.

## 버전

- `versionCode`: 17
- `versionName`: 2.0.0
- APK: `Counseling_07_29_v2.0.0_debug.apk`

## 현재 검증

```text
python -m unittest discover -s Jetson -p "test_*.py"
PASS: 52 tests

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon --no-problems-report
PASS

Frontend/gradlew.bat :app:lintDebug --no-daemon --no-problems-report
PASS: 0 errors

Frontend/gradlew.bat :app:assembleDebug --no-daemon --no-problems-report
PASS

bash -n Jetson/install_user_service.sh
PASS
```

## 실제 Jetson 배포 상태

- `onmom-derived-insights.service`: active
- LAN `/health`: `status=ok`
- Jetson에서도 전체 52개 테스트 통과
- Home Assistant 실제 장치 6개를 기기별 허용 목록에 등록
- 앱 응답에는 Home Assistant `entity_id`와 토큰을 노출하지 않음
- 배포 전 백업:
  `/home/iot/.jetson_backup/Jetson-pre-v2.0.0-20260729-200341.tar.gz`
- 문맥 DB 백업:
  `/home/iot/.jetson_backup/onmom_context-pre-v2.0.0-20260729-200341.db`

## 남은 실기기 확인

- Jetson의 `~/.config/onmom/home_assistant_token`이 아직 없으므로 IoT 장치는
  목록에 표시되지만 실제 제어는 토큰 저장 전까지 비활성이다.
- Android 실기기가 연결되지 않아 역할별 UI, 알림 권한, 보호자 위험 알림의
  최종 실기기 확인은 APK 설치 후 수행한다.
