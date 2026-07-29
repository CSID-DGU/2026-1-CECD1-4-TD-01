# 2026-07-29 적응형 상담·카메라 브리지 보강

## Android v1.2.1

- 음성 감정 결과가 없을 때 사용자 텍스트를 기기 안에서만 제한적으로 분석한다.
- 대화 원문은 Jetson으로 보내지 않고 valence, arousal, confidence만 파생한다.
- 부정 표현의 부정형(`불안하지 않아`)은 정서 신호로 만들지 않는다.
- 음성 결과의 신뢰도가 `0.45` 이상이면 텍스트 fallback보다 우선한다.
- Jetson 대화 이벤트에서 정서 필드를 공통 이름으로 저장하고 출처를 `VOICE` 또는 `TEXT`로 구분한다.
- 상담 전후 비교 대기값은 앱 재시작 뒤에도 유지하지만 6시간이 지나면 폐기한다.
- Jetson 설정 화면에서 연결 상태, 상담 결과 수, 학습 대기 수, 정책 버전을 표시한다.

## Jetson 카메라 브리지

- `camera_analysis_bridge.py`가 `127.0.0.1:8766/UDP`에서 `camera-sample-v1` 파생 샘플을 받는다.
- loopback 이외 주소에는 바인딩하지 않는다.
- 활동, 자세, 얼굴 표정, rPPG를 60초 창으로 집계한다.
- 원본 프레임, URI, landmark, embedding, base64, 장치 식별자는 스키마에 없으므로 거부한다.
- 얼굴이 정확히 한 명일 때만 표정 정서를 사용한다.
- 얼굴 표정은 `FACIAL_EXPRESSION`, rPPG는 `PHYSIOLOGICAL_STATE` 도메인에 저장한다.
- `/health`의 `camera_bridge` 필드에서 수신·거절·중복·집계 이벤트 수와 마지막 오류를 확인한다.
- systemd 설치 스크립트가 카메라 브리지까지 Python 문법 검사한다.

## 느린 자가 적응 안전장치

- 학습률은 세션당 `0.01`이다.
- 한 번의 수면 학습에서 각 전략의 순변화는 최대 `±0.02`다.
- 한꺼번에 많은 미학습 결과가 있어도 밤사이 급격한 정책 변화가 일어나지 않는다.
- 정책 가중치는 계속 `0.50~1.50` 범위와 버전 롤백을 유지한다.
- 상담 전후 정서 비교 간격이 6시간을 넘으면 서버에서도 거부한다.

## 검증

```text
python -m unittest discover -s Jetson -v
PASS: 29 tests

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon
PASS

JetsonAdaptiveContextTest
PASS: 9 tests

python -m py_compile Jetson/context_engine.py Jetson/camera_analysis_bridge.py Jetson/derived_insight_server.py Jetson/onmom_client.py
PASS

bash -n Jetson/install_user_service.sh
PASS

Frontend/gradlew.bat :app:assembleDebug --no-daemon
PASS
```

산출물:

- `Frontend/app/build/outputs/apk/debug/Counseling_07_29_v1.2.1_debug.apk`
- `Jetson_OnMom_07_29_v1.2.1.zip`

## Android v1.2.2 / Jetson 외부 정책 설정

- Jetson의 데이터 품질, 개인 변화, RFID, 정서 구간, 보상, 학습, 카드 선택, 보존 기준을 `adaptive_policy.ini`로 분리했다.
- INI는 주석과 단위, 수정 시 주의사항을 함께 제공하며 외부 Python 패키지 없이 표준 `configparser`로 읽는다.
- 누락, 타입 오류, 안전 범위 오류, 프롬프트 전략 이름 오타가 있으면 서비스 시작을 중단한다.
- `NEGATIVE_HIGH_AROUSAL`, `NEGATIVE_LOW_AROUSAL`, `NEUTRAL`, `POSITIVE` 상황별 프롬프트를 INI에서 직접 수정할 수 있다.
- 여섯 응답 전략은 이름만 모델에 전달하지 않고, INI의 구체적인 한국어 행동 지침과 함께 전달한다.
- Android의 주제 키워드, 텍스트 정서 fallback, 음성 정서 좌표, 최소 신뢰도, fallback 전략 기준을 `JetsonAdaptiveClientPolicy.kt`로 분리했다.
- `/health`가 적용 중인 설정 버전, 파일 이름, 학습률, 최대 정책 변화, 가중치 범위를 표시한다.
- 서버에 `--adaptive-config` 옵션을 추가하고 systemd 설치 스크립트가 기본 INI를 명시적으로 전달한다.
- `ADAPTIVE_POLICY_GUIDE.md`에 항목별 의미, 적용 명령, 추천 수정 순서를 정리했다.
- 앱 버전은 `1.2.2`(`versionCode 13`)이며 디버그 APK 이름은 `Counseling_07_29_v1.2.2_debug.apk`다.

## 검증

```text
python -m unittest discover -s Jetson -v
PASS: 33 tests

python -m py_compile Jetson/adaptive_config.py Jetson/context_engine.py Jetson/derived_insight_server.py Jetson/camera_analysis_bridge.py Jetson/onmom_client.py
PASS

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon --no-problems-report
PASS

Frontend/gradlew.bat :app:assembleDebug --no-daemon --no-problems-report
PASS
```

산출물:

- `Frontend/app/build/outputs/apk/debug/Counseling_07_29_v1.2.2_debug.apk`
- `Jetson_OnMom_07_29_v1.2.2.zip`

## Android v1.2.3 / 감정 변화 암묵적 피드백

- 현재 발화의 정서 구간은 다음 답변의 상황 프롬프트와 전략 선택에 즉시 반영한다.
- 상담 전후 정서 보상이 `+0.05` 이상이면 `LIKE`(편안 방향), `-0.05` 이하면 `DISLIKE`(격양 방향), 사이는 `NEUTRAL`로 분류한다.
- Jetson의 `/v1/session-outcomes` 응답과 감사 로그에 `implicit_feedback`을 남긴다.
- `/v1/learning-status`가 최근 결과의 `implicit_feedback_counts`를 반환한다.
- Android Jetson 상태 화면에 편안 방향·중립·격양 방향 누적 수를 표시하고, 모델 본체가 아닌 응답 전략 정책을 학습한다는 설명을 추가했다.
- 앱 버전은 `1.2.3`(`versionCode 14`)이며 디버그 APK 이름은 `Counseling_07_29_v1.2.3_debug.apk`다.

## 검증

```text
python -m unittest discover -s Jetson -p "test_*.py"
PASS: 35 tests

python -m py_compile Jetson/adaptive_config.py Jetson/context_engine.py Jetson/derived_insight_server.py
PASS

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon --no-problems-report
PASS

Frontend/gradlew.bat :app:assembleDebug --no-daemon --no-problems-report
PASS

bash -n Jetson/install_user_service.sh
PASS
```

산출물:

- `Frontend/app/build/outputs/apk/debug/Counseling_07_29_v1.2.3_debug.apk`
- `Jetson_OnMom_07_29_v1.2.3.zip`

## Android v1.3.0 / 연속형 응답 스타일 개인화

- 네 정서 구간마다 답변 길이, 공감 비중, 질문 사용, 조언 직접성, 안정화 강도, 설명 상세도, 주도성, 따뜻함, 행동 크기, 건강정보 언급의 10개 연속값을 저장한다.
- 한 답변에서는 기본 2개 축만 작은 범위로 탐색하고 Android가 적용값·기준값·탐색 축을 다음 상담 결과에 포함해 반환한다.
- 편안 방향 보상은 적용 방향을 강화하고 격양 방향 보상은 반대로 갱신한다.
- 수면 gate 학습에서 축당 한 번에 최대 `0.03`만 이동하며 값은 `0.05~0.95` 범위를 벗어나지 않는다.
- 연속값은 문장 수, 공감 비중, 질문 수, 설명 항목 수, 행동 시간 등이 포함된 구체적인 동적 프롬프트로 변환된다.
- 기존 v1 SQLite DB는 상담 결과와 정책을 유지한 채 스타일 테이블과 버전 스냅샷을 자동 추가한다.
- Android Jetson 상태 화면에 스타일 변화 축 수와 누적 탐색 관찰 수를 표시한다.
- 앱 버전은 `1.3.0`(`versionCode 15`)이며 APK 이름은 `Counseling_07_29_v1.3.0_debug.apk`다.

## 검증

```text
python -m unittest discover -s Jetson -p "test_*.py"
PASS: 39 tests

Frontend/gradlew.bat :app:testDebugUnitTest --no-daemon --no-problems-report
PASS

Frontend/gradlew.bat :app:assembleDebug --no-daemon --no-problems-report
PASS

bash -n Jetson/install_user_service.sh
PASS
```

산출물:

- `Frontend/app/build/outputs/apk/debug/Counseling_07_29_v1.3.0_debug.apk`
- `Jetson_OnMom_07_29_v1.3.0.zip`
