# 2026-07-29 개발자 구조화 분석 스냅샷

## Android v1.4.0

- 기존 상담용 파생 요약 전송은 그대로 유지한다.
- 개발자 화면에 `개발자 분석 수치만 수동 전송` 버튼을 추가했다.
- 수동 전송은 `HEALTH`, `PHENOTYPE`, `GALLERY` 구조화 데이터셋을 만든다.
- 건강 데이터는 기간 합계, 날짜별 유효값, 제외 사유, 운동·수면·생체·체성분·영양의 구조화 수치를 포함한다.
- 피노타입은 통화량·변화율과 스크린타임·야간 사용·앱 표시 이름·분류·사용 시간을 포함한다.
- Gallery는 분석 품질, 활동별 빈도·비율·신뢰도, 기간 변화, 행동 점수, 웰빙 도메인 점수와 보호 자원을 포함한다.
- Gallery 원본 사진, URI, 파일 경로, 전화번호, 연락처 이름, 패키지명, 대화문과 음성은 포함하지 않는다.
- 앱에서 원본 표식이 발견되면 네트워크 요청 전에 전송을 중단한다.

## Jetson

- `POST /v1/analysis-snapshots`와 `GET /v1/analysis-snapshots`를 추가했다.
- 스냅샷은 상담용 이벤트·요약과 분리된 `analysis_snapshots` 테이블에 저장한다.
- 상담 프롬프트 생성과 개인화 학습은 분석 스냅샷 테이블을 읽지 않는다.
- 중복 `snapshot_id`는 다시 저장하지 않는다.
- JSON 크기, 깊이, 항목 수, 숫자의 유한성, 허용 카테고리와 금지 필드를 검증한다.
- 기본 보존 기간은 30일이며 `analysis_snapshots_days`로 수정할 수 있다.
- `python3 onmom_client.py analysis-snapshots`로 수신 자료를 조회할 수 있다.

## 버전

- `versionCode`: 16
- `versionName`: 1.4.0
- APK: `Counseling_07_29_v1.4.0_debug.apk`

## 검증

```text
python -m unittest discover -s Jetson -p "test_*.py"
PASS: 44 tests

python -m py_compile Jetson/analysis_snapshot.py Jetson/adaptive_config.py
Jetson/context_engine.py Jetson/derived_insight_server.py Jetson/onmom_client.py
PASS

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon --no-problems-report
PASS

Frontend/gradlew.bat :app:assembleDebug --no-daemon --no-problems-report
PASS

bash -n Jetson/install_user_service.sh
PASS
```

산출물:

- `Frontend/app/build/outputs/apk/debug/Counseling_07_29_v1.4.0_debug.apk`
- `Jetson_OnMom_07_29_v1.4.0.zip`
