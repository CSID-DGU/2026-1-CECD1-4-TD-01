# On-mom Jetson Nano 적응형 상담 컨텍스트 서비스

이 문서는 현재 저장소에 구현된 서비스를 바로 Jetson Nano에서 실행하고 연결하는 방법이다. 별도의 18주 개발 단계를 전제로 하지 않는다.

## 기준과 프롬프트 직접 수정

자가 적응의 수치·판정 기준과 상황별 프롬프트는 `adaptive_policy.ini`에 있다. 각 값의 의미, 안전한 수정 순서, Android 로컬 기준은 `ADAPTIVE_POLICY_GUIDE.md`를 참고한다.

설정 변경 후:

```bash
python3 -m unittest discover -s . -p "test_*.py"
systemctl --user restart onmom-derived-insights.service
curl http://localhost:8765/health
```

서비스는 시작할 때 설정을 검증한다. 누락, 오타, 범위 오류가 있으면 잘못된 정책으로 실행하지 않고 시작을 중단한다.

## 현재 동작하는 흐름

```text
상담 앱 파생 요약 ─────────────┐
상담 주제·정서 전후 결과 ─────┤
Home Assistant RFID·문·수면 ─┤
Jetson 자세·표정·rPPG 모듈 ──┤
                              v
                    SQLite 누적 저장
                              v
             결측·0·범위·모순·품질 검사
                              v
                 개인 28일 기준선 비교
                              v
             상담용 ContextCard 최대 3개
                              v
             Android 온디바이스 LLM 프롬프트

사용자가 자는 중이라고 확인됨
  -> 미학습 상담 전후 결과 계산
  -> 응답 전략 가중치를 최대 0.01 학습률로 미세 갱신
  -> 새 정책 버전 저장
```

LLM 전체 파라미터를 매일 다시 학습하지 않는다. 현재 자가학습 대상은 다음 여섯 응답 전략의 정서 상태별 가중치다.

- `EMPATHIC_REFLECTION`
- `OPEN_QUESTION`
- `GROUNDING`
- `POSITIVE_REINFORCEMENT`
- `MICRO_ACTION`
- `QUIET_PRESENCE`

가중치는 `0.50~1.50` 범위를 벗어날 수 없고 버전별로 보존된다.

## 1. Jetson 설치 또는 업데이트

업데이트된 `Jetson` 폴더를 Jetson의 `~/Jetson`에 복사한 다음:

```bash
cd ~/Jetson
sh install_user_service.sh
```

설치 스크립트가 수행하는 작업:

- Python 문법 검사
- 영구 인증 토큰 생성 또는 기존 토큰 유지
- SQLite 데이터 폴더 생성
- 사용자 systemd unit 검증
- 이전 `bad-setting` 실패 상태 초기화
- 서비스 등록, 자동 시작, 즉시 실행

로그인하지 않은 상태에서도 실행하려면 한 번만:

```bash
sudo loginctl enable-linger "$USER"
```

확인:

```bash
systemctl --user status onmom-derived-insights.service --no-pager -l
curl http://localhost:8765/health
```

정상 응답의 핵심:

```json
{
  "status": "ok",
  "schema": "derived-only-v1",
  "context_engine": {
    "status": "ok",
    "schema": "adaptive-context-v1"
  }
}
```

데이터:

```text
~/Jetson/data/onmom_context.db
~/Jetson/data/latest_derived_insights.json
```

토큰:

```text
~/.config/onmom/jetson_sync_token
```

## 2. Android 상담 앱 연결

앱의 `설정 → Jetson 파생 정보 전송`에서 다음을 저장한다.

```text
주소: http://JETSON_사설_IP:8765/v1/derived-insights
인증 토큰: 설치 스크립트가 표시한 토큰
```

개발자 APK에서는 `10.x`, `172.16~31.x`, `192.168.x`, `169.254.x` 사설 IP의 HTTP 연결을 허용한다. IP가 바뀌면 앱 설정의 주소만 바꾸면 된다.

토큰이 저장된 뒤 상담할 때 앱이 자동으로 수행하는 작업:

1. Jetson의 누적 컨텍스트와 학습된 전략 조회
2. 현재 대화 주제와 음성 정서를 쿼리에 포함
3. 받은 `prompt_context`를 최신 사용자 메시지에만 삽입
4. Health/Gallery/생활 패턴/음성 감정 파생 요약을 Jetson에 누적
5. 대화 원문 대신 주제, 길이 구간, 음성 여부, 사용 전략 기록
6. 다음 음성 발화가 들어오면 직전 상담 전후 정서 변화 전송

Jetson 연결이 실패하면 이 과정만 건너뛰고 기존 온디바이스 상담을 계속한다.

## 3. Home Assistant 연결

예제 파일:

```text
Jetson/home_assistant/package_onmom.yaml.example
```

Home Assistant 설정에서 packages가 아직 없다면:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

예제 파일을 다음 위치로 복사한다.

```text
/config/packages/onmom.yaml
```

`secrets.yaml`:

```yaml
onmom_jetson_event_url: "http://JETSON_사설_IP:8765/v1/analysis-events"
onmom_jetson_authorization: "Bearer JETSON_TOKEN"
```

설정 검사를 통과한 뒤 Home Assistant를 재시작한다.

기존 RFID 입실 자동화에서:

```yaml
- action: script.onmom_rfid_enter
```

퇴실 자동화에서:

```yaml
- action: script.onmom_rfid_exit
```

문 센서는 상태에 따라 다음 스크립트를 호출한다.

```yaml
- action: script.onmom_door_open
```

```yaml
- action: script.onmom_door_closed
```

RFID UID는 Jetson payload에 넣지 않는다. `ENTER/EXIT`, 구역, 허가 여부만 전달된다. 거절된 출입은 보안 로그로 분류되어 웰빙 분석에서 제외된다.

### 수면 판단 연결

패키지는 다음 helper를 만든다.

```text
input_boolean.onmom_user_sleeping
```

침대 센서, 재실 센서 또는 기존 수면 판단 자동화가 이 helper를 켜고 끄게 한다.

```yaml
actions:
  - action: input_boolean.turn_on
    target:
      entity_id: input_boolean.onmom_user_sleeping
```

기상 시:

```yaml
actions:
  - action: input_boolean.turn_off
    target:
      entity_id: input_boolean.onmom_user_sleeping
```

수면 중에는 15분마다 새 수면 증거가 전달된다. Jetson은 최근 45분 안의 신호이며 `confidence × coverage >= 0.70`일 때만 자동 학습한다.

## 4. Jetson 카메라 분석 모듈 연결

모든 예시는 원본 프레임을 보내지 않고 파생 결과만 로컬 서비스에 보낸다.

### rPPG

```bash
python3 onmom_client.py event \
  --source hub.camera \
  --source-family physiology.heart_rate \
  --domain PHYSIOLOGICAL_STATE \
  --event-type rppg_window \
  --ttl-minutes 30 \
  --confidence 0.91 \
  --coverage 0.88 \
  --metrics-json '{
    "heart_rate_bpm": 72,
    "signal_quality": 0.86,
    "measurement_seconds": 30
  }'
```

다음 값은 중앙 품질 gate에서 심박을 제외한다.

- `35~220 bpm` 바깥
- rPPG 신호 품질 `0.45` 미만
- 측정 시간 10초 미만
- NaN/Infinity

### 자세

```bash
python3 onmom_client.py event \
  --source hub.camera \
  --source-family posture.camera \
  --domain SEDENTARY_AND_POSTURE \
  --event-type posture_window \
  --confidence 0.85 \
  --coverage 0.80 \
  --metrics-json '{
    "observed_seconds": 900,
    "slouch_minutes": 7.5,
    "upright_ratio": 0.62,
    "slouch_ratio": 0.38
  }'
```

관측 시간이 60초 미만이거나 비율이 `0~1` 밖이면 해당 자세 값은 학습에서 제외된다.

### 표정 또는 현재 정서

정서 모델의 출력을 `valence -1~1`, `arousal 0~1`로 변환한 뒤:

```bash
python3 onmom_client.py event \
  --source hub.camera \
  --source-family emotion.face \
  --domain VOICE_EMOTION \
  --event-type current_emotion \
  --ttl-minutes 15 \
  --confidence 0.72 \
  --coverage 0.75 \
  --metrics-json '{"valence":-0.35,"arousal":0.68}'
```

정서 추정은 응답 전략 선택에만 사용하며 사용자의 직접 표현보다 우선하지 않는다.

## 5. 상태와 학습 확인

Jetson에서:

```bash
python3 onmom_client.py status
```

주요 필드:

- `learning_state`: `COLLECTING`, `EARLY_SIGNAL`, `TREND_AVAILABLE`
- `session_outcomes`: 누적 상담 전후 결과
- `trained_outcomes`: 야간 학습 완료 수
- `pending_outcomes`: 다음 수면 학습 대기 수
- `recent_mean_reward`
- `previous_mean_reward`
- `reward_trend_delta`
- `bucket_mean_rewards`
- `policy_weight_range`
- `policy_versions`

이 값은 내부 적응 방향을 관찰하기 위한 것이며 상담 효과나 건강 개선을 증명하지 않는다.

현재 정책:

```bash
python3 onmom_client.py policy
```

현재 컨텍스트:

```bash
python3 onmom_client.py context \
  --topic SLEEP_AND_ROUTINE \
  --topic PHYSIOLOGICAL_STATE
```

개발 중 강제 1회 학습:

```bash
python3 onmom_client.py adapt --force
```

운영 중에는 강제 실행보다 Home Assistant 수면 gate를 사용한다.

정책 롤백:

```bash
python3 onmom_client.py rollback --version 1
```

롤백도 새 정책 버전으로 기록되어 감사 이력이 남는다.

## 6. API

| Method | Path | 기능 |
|---|---|---|
| GET | `/health` | 서비스·DB·수면 gate 상태 |
| POST | `/v1/derived-insights` | 기존 Android 파생 요약 |
| POST | `/v1/analysis-events` | IoT/카메라/앱 파생 이벤트 |
| POST | `/v1/session-outcomes` | 상담 전후 정서와 사용 전략 |
| GET | `/v1/context` | 현재 상담용 컨텍스트 |
| GET | `/v1/policy` | 현재 정책 가중치 |
| GET | `/v1/learning-status` | 학습 관찰 지표와 버전 |
| POST | `/v1/nightly-adapt` | 수면 gate 기반 학습 요청 |
| POST | `/v1/policy/rollback` | 저장된 정책 복원 |

`/health` 이외에는 Bearer 토큰이 필요하다.

## 7. 데이터 보존과 제외

- AnalysisEvent: 기본 30일
- ContextCard: 만료 또는 90일
- 감사 로그: 180일
- 정책 버전과 상담 결과: 현재 구현에서는 명시 삭제 전까지
- 음성 감정과 현재 정서: 짧은 TTL

다음은 수신 단계에서 거부한다.

- 사진·음성 바이트
- `content://`, `file://`, `/storage/`, base64 미디어
- 전화번호
- 앱 package name 원문
- RFID UID
- 대화 transcript와 현재 사용자 메시지 원문

다음은 저장할 수 있지만 기준선과 프롬프트 계산에서 제외한다.

- `MISSING`, `SUSPECT`, `CONTRADICTED`, `EXPIRED`, `EXCLUDED`
- 0 또는 음수 걸음/운동 기록
- 0.02km 이하 거리
- 걸음당 0.2m 미만 또는 2m 초과 거리
- 품질이 낮거나 너무 짧은 rPPG
- 방향 불명 RFID
- 거절된 출입 보안 이벤트

## 8. 감정 변화 피드백 해석

- 현재 감정 구간은 다음 답변의 상황별 프롬프트와 전략 선택에 즉시 반영된다.
- 답변 전후 보상이 `+0.05` 이상이면 `LIKE`(편안 방향), `-0.05` 이하면 `DISLIKE`(격양 방향), 사이는 `NEUTRAL`로 집계된다.
- `/v1/learning-status`의 `implicit_feedback_counts`에서 최근 결과의 `LIKE`, `NEUTRAL`, `DISLIKE` 수를 확인할 수 있다.
- Android의 Jetson 상태 화면에는 같은 값을 `편안 방향`, `중립`, `격양 방향`으로 표시한다.
- 이 평가는 다음 수면 gate 학습에서 응답 전략 가중치에 반영된다. LLM 본체 파라미터를 재학습하지 않는다.

## 9. 연속형 스타일 정책

`/v1/context`는 전략 순위와 함께 `response_style`을 반환한다.

```json
{
  "values": {"RESPONSE_LENGTH": 0.31},
  "baseline_values": {"RESPONSE_LENGTH": 0.25},
  "explored_dimensions": ["RESPONSE_LENGTH", "WARMTH"]
}
```

실제 응답에는 10개 축이 모두 포함되며 위 예시는 구조 설명을 위해 줄였다. Android는 이 값을 프롬프트에 사용한 뒤 다음 `session-outcome-v1`에 그대로 포함한다. 구버전 Android가 `response_style`을 보내지 않아도 전략 학습은 계속 동작하고 스타일 학습만 생략한다.

스타일 중심값은 정서 구간별 10개, 총 40개가 SQLite에 저장된다. 한 답변에서는 기본 2개만 탐색하고, 편안·격양 보상을 이용해 탐색한 축만 갱신한다. 전략 가중치와 스타일 값은 같은 `policy_versions` 행에 보존되어 함께 롤백된다.
