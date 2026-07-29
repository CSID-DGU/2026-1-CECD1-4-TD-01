# 적응형 상담 기준·프롬프트 수정 안내

실제로 수정할 파일은 두 개입니다.

| 파일 | 적용 위치 | 적용 방법 |
|---|---|---|
| `adaptive_policy.ini` | Jetson의 품질 검사, 개인 기준선, 정서 구간, 보상, 학습률, 상황별 프롬프트 | Jetson 서비스 재시작 |
| `Frontend/.../JetsonAdaptiveClientPolicy.kt` | 휴대폰 안의 주제 키워드, 텍스트 정서 fallback, 음성 정서 좌표, 최소 정서 신뢰도 | APK 재빌드·설치 |

대부분의 개인화 동작은 `adaptive_policy.ini`만 고치면 됩니다. Android 파일은 Jetson에 보내기 전 신호 생성 방법을 바꿔야 할 때만 수정하세요.

## 1. Jetson 설정 적용

Jetson에서:

```bash
cd ~/Jetson
nano adaptive_policy.ini
python3 -m unittest discover -s . -p "test_*.py"
systemctl --user restart onmom-derived-insights.service
curl http://localhost:8765/health
```

`/health` 응답의 `context_engine.adaptive_config`에 다음과 비슷한 값이 있으면 적용된 것입니다.

```json
{
  "config_version": 1,
  "file": "adaptive_policy.ini",
  "learning_rate": 0.01,
  "max_policy_delta_per_run": 0.02,
  "weight_range": [0.5, 1.5]
}
```

설정은 서비스 시작 시 한 번 읽습니다. 파일만 저장하고 서비스를 재시작하지 않으면 이전 값이 계속 사용됩니다.

설정값이 없거나 범위를 벗어나거나 프롬프트 전략 이름을 잘못 쓰면 서비스가 시작되지 않습니다. 이 동작은 잘못된 설정을 조용히 사용하는 것보다 안전하도록 의도된 것입니다.

## 2. INI 섹션별 역할

### `[quality]`

센서값을 계산에 넣기 전에 제외할 기준입니다.

- `steps_missing_at_or_below`: 이 값 이하의 걸음 수를 누락으로 취급
- `exercise_minutes_missing_at_or_below`: 이 값 이하의 운동 시간을 누락으로 취급
- `distance_km_missing_at_or_below`: 지나치게 작은 이동거리 제외
- `meters_per_step_min/max`: 걸음 수와 거리의 모순 검사
- `heart_rate_bpm_min/max`: 지원할 심박 입력 범위
- `rppg_signal_quality_min`: rPPG 최소 신호 품질
- `rppg_measurement_seconds_min`: rPPG 최소 측정 시간
- `posture_observation_seconds_min`: 자세 비율을 사용할 최소 관측 시간

이 값은 건강 이상을 판정하는 임계값이 아니라 **데이터 품질 임계값**입니다.

### `[trend]`

개인의 평소 범위와 최근 변화를 비교합니다.

- `recent_window_hours`: 최근값 집계 구간
- `baseline_window_days`: 과거 개인 기준선 구간
- `min_baseline_samples`: 기준선을 계산할 최소 표본
- `robust_z_candidate_threshold`: MAD 기반 변화 후보 기준
- `relative_change_candidate_threshold`: 상대 변화 보조 기준
- `min_card_confidence`: 프롬프트 카드로 만들 최소 신뢰도

기본 `robust_z=1.5`는 엄격한 통계적 이상치가 아니라 민감한 변화 후보입니다. 엄격하게 만들고 싶으면 `2.5` 정도로 올리고, 잠재적 이상치에 가깝게 보려면 `3.5`를 사용하세요.

### `[rfid]`

입실 시각의 개인 기준선 변화를 계산합니다.

- 최근 7일 대 이전 28일
- 최근 최소 3건, 기준선 최소 5건
- 대표 입실 시각이 45분 이상 달라질 때 카드 생성
- 같은 리더·구역의 10초 이내 반복 인식은 중복 제외

RFID만으로 실제 재실 시간, 사회적 고립, 수면 문제를 확정하지 않습니다.

### `[emotion]`

`valence(-1~1)`와 `arousal(0~1)`을 네 상태로 나눕니다.

| 상태 | 기본 조건 |
|---|---|
| `NEGATIVE_HIGH_AROUSAL` | `valence ≤ -0.25`, `arousal ≥ 0.55` |
| `NEGATIVE_LOW_AROUSAL` | `valence ≤ -0.25`, `arousal < 0.55` |
| `POSITIVE` | `valence ≥ 0.25` |
| `NEUTRAL` | 나머지 |

경계는 `[emotion]`에서 바꿀 수 있습니다. 구간을 바꾸면 어떤 상황별 프롬프트와 전략 가중치가 선택되는지도 함께 달라집니다.

### `[reward]`

답변 전후 정서로 전략 보상을 계산하는 비율입니다.

- 부정 상태: 정서의 긍정 방향 이동 65%, 각성 완화 35%
- 중립 상태: 정서 이동 50%, 각성 완화 50%
- 긍정 상태: 긍정 유지 55%, 정서 이동 25%, 불필요한 각성 증가 감점 20%
- 명시적 평가가 연결되면 자동 관측 70%, 좋아요·싫어요 30%

각 묶음의 비율 합은 반드시 `1.0`이어야 합니다. 현재 Android 화면에는 좋아요·싫어요가 연결되지 않아 `explicit_feedback_weight`는 실제 보상에 아직 사용되지 않습니다.

### `[learning]`

자가 적응 속도와 안전 범위입니다.

- `learning_rate`: 상담 결과 한 건의 기본 학습률
- `max_delta_per_run`: 한 번의 수면 학습에서 전략 하나의 최대 변화
- `min_weight/max_weight`: 전략 가중치 안전 범위
- `min_training_interval_hours`: 자동 학습 최소 간격
- `sleep_evidence_max_age_minutes`: 수면 신호 유효 시간
- `sleep_min_confidence`: 수면 신뢰도 기준
- `max_session_outcome_interval_hours`: 답변 전 정서와 다음 정서를 연결할 최대 시간
- `early_signal_min_outcomes/trend_available_min_outcomes`: 상태 화면의 학습 단계 기준

학습률을 크게 올리면 적은 상담 결과에도 전략 순위가 급격히 바뀔 수 있습니다. 먼저 `0.005~0.02` 범위에서 관찰하는 편이 안전합니다.

### `[context]`와 `[retention]`

`[context]`는 한 답변에 사용할 카드 수, 전략 수와 관련성 점수를 조절합니다. `[retention]`은 SQLite 이벤트·카드·감사 로그 보존 기간을 조절합니다.

## 3. 상황별 프롬프트 수정

### `[prompt.common]`

모든 상황에 항상 적용되는 규칙입니다. 진단 금지, 현재 사용자 발화 우선, 센서 수치 직접 나열 금지 같은 안전 원칙을 둡니다.

### `[prompt.bucket]`

네 정서 상태에 따른 큰 답변 방향입니다.

```ini
[prompt.bucket]
NEGATIVE_HIGH_AROUSAL =
    사용자가 압도되거나 긴장했을 가능성을 고려해 짧고 차분하게 답합니다.
    해결책 전에 공감하고 안정화 선택지 하나만 제안합니다.
```

여러 줄을 쓸 때 두 번째 줄부터도 반드시 공백으로 들여써야 합니다.

### `[prompt.strategy]`

자가학습으로 순위가 달라지는 여섯 전략의 실제 행동 지침입니다.

```ini
[prompt.strategy]
MICRO_ACTION =
    지금 또는 오늘 할 수 있는 작고 구체적인 행동 하나만 제안하세요.
    실패해도 부담이 적고 5분 안팎으로 시작할 수 있는 크기를 우선하세요.
```

전략 이름은 데이터베이스와 API 계약에 사용되므로 바꾸면 안 됩니다. `=` 오른쪽 지침만 수정하세요.

실제 프롬프트 구성 순서는 다음과 같습니다.

```text
공통 안전 규칙
→ 현재 정서 구간의 prompt.bucket
→ 현재 구간에서 가중치가 높은 prompt.strategy
→ 품질 검사를 통과한 관련 맥락 카드
→ 현재 사용자 메시지
```

## 4. Android 로컬 기준

`JetsonAdaptiveClientPolicy.kt`에는 다음 값이 있습니다.

- 음성/텍스트 정서 최소 신뢰도 `0.45`
- 텍스트 긍정·불안·피로 등의 키워드
- 부정형 제외 전 로컬 정서 계산 가중치
- 음성 감정 범주를 valence/arousal로 바꾸는 좌표
- 주제별 키워드
- fallback 전략 선택 경계
- 상담 결과 연결 시간 6시간

이 파일은 APK에 컴파일되므로 수정한 뒤:

```powershell
cd Frontend
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

새 APK를 휴대폰에 설치해야 적용됩니다.

## 5. 추천 수정 순서

한꺼번에 여러 기준을 바꾸면 무엇 때문에 답변이 달라졌는지 알기 어렵습니다.

1. 상황별 프롬프트 문장만 수정
2. 일주일 이상 결과 관찰
3. 카드 신뢰도 또는 정서 구간 하나만 수정
4. 다시 관찰
5. 마지막에 학습률이나 보상 비율 수정

수정 전 `adaptive_policy.ini`를 날짜가 붙은 복사본으로 보관하면 정책 버전 롤백과 별개로 설정 자체도 되돌릴 수 있습니다.

## 6. 실시간 감정과 암묵적 좋아요·싫어요

답변 정책 변화는 세 단계로 나뉩니다.

1. 현재 발화에서 얻은 감정 좌표를 네 정서 구간으로 분류하고, 다음 답변을 만들 때 해당 `prompt.bucket`과 우선 전략을 즉시 선택합니다. 이미 생성 중인 문장 한가운데서 정책을 바꾸지는 않습니다.
2. 답변 전 정서와 다음으로 신뢰할 수 있게 측정된 정서를 비교해 보상을 계산합니다. 연결 가능한 최대 간격은 기본 6시간입니다.
3. 사용자가 자는 중이라는 신뢰 가능한 근거가 있을 때 미학습 결과를 모아 전략 가중치를 천천히 갱신합니다.

`[reward]`의 기본 암묵적 평가 경계는 다음과 같습니다.

| 계산 보상 | 표시 | 의미 |
|---|---|---|
| `+0.05` 이상 | `LIKE` / 편안 방향 | 답변 뒤 정서가 충분히 편안한 방향으로 이동 |
| `-0.05` 초과, `+0.05` 미만 | `NEUTRAL` / 중립 | 변화가 작아 선호 신호로 단정하지 않음 |
| `-0.05` 이하 | `DISLIKE` / 격양 방향 | 답변 뒤 정서가 충분히 격양되는 방향으로 이동 |

수정할 키는 아래 두 개입니다.

```ini
[reward]
implicit_like_threshold = 0.05
implicit_dislike_threshold = -0.05
```

양수 경계를 낮추면 작은 편안 변화도 LIKE로 많이 잡히고, 음수 경계를 0에 가깝게 올리면 작은 격양 변화도 DISLIKE로 많이 잡힙니다. 센서 오차를 선호로 오인하지 않도록 처음에는 기본값을 유지하고 최소 20~30개 결과를 관찰한 뒤 한 번에 한 경계만 조정하는 것을 권장합니다.

이 기능이 학습하는 것은 LLM 본체의 신경망 가중치가 아닙니다. 정서 구간별 여섯 응답 전략의 선택 가중치를 바꾸고, 그 결과 다음 프롬프트의 행동 지침과 순서가 달라져 답변이 간접적으로 개인화됩니다. `LIKE/DISLIKE`는 개인화용 관찰 신호이며 상담 효과나 건강 개선을 증명하는 지표가 아닙니다.

## 7. 연속형 응답 스타일 개인화

기존의 네 정서 구간과 여섯 전략 순위 위에 다음 10개 값을 `0.0~1.0` 연속값으로 적용합니다.

| 설정 키 | 의미 | 낮을 때 | 높을 때 |
|---|---|---|---|
| `RESPONSE_LENGTH` | 답변 길이 | 약 2문장 | 약 8문장 |
| `EMPATHY_RATIO` | 공감 비중 | 정리·제안 중심 | 감정 반영 중심 |
| `QUESTION_FREQUENCY` | 질문 사용 | 질문 없이 반영 | 한 가지 질문 사용 |
| `ADVICE_DIRECTNESS` | 조언 직접성 | 허락을 구하는 선택지 | 짧고 명확한 제안 |
| `GROUNDING_INTENSITY` | 안정화 개입 강도 | 거의 제안하지 않음 | 긴장 시 적극 제안 |
| `EXPLANATION_DETAIL` | 설명 상세도 | 핵심 1개 | 핵심 4개 |
| `PROACTIVITY` | 대화 주도성 | 경청·확인 중심 | 다음 단계 제안 중심 |
| `WARMTH` | 정서적 따뜻함 | 담백하고 중립적 | 따뜻한 반영 중심 |
| `ACTION_SIZE` | 행동 제안 크기 | 약 2분 | 약 15분 |
| `HEALTH_MENTION` | 건강·생활 정보 언급 | 사용자가 물을 때만 | 관련성이 높을 때 연결 |

초기값은 정서 구간마다 `[style.NEGATIVE_HIGH_AROUSAL]`, `[style.NEGATIVE_LOW_AROUSAL]`, `[style.NEUTRAL]`, `[style.POSITIVE]`에서 직접 바꿀 수 있습니다. 예를 들어 고각성 부정 상태의 답변을 더 짧게 하려면:

```ini
[style.NEGATIVE_HIGH_AROUSAL]
RESPONSE_LENGTH = 0.20
```

실제 프롬프트 문장은 `[prompt.style]`에서 수정합니다. `{value}`, `{percent}`, `{level}`, `{target_sentences}`, `{max_questions}`, `{detail_points}`, `{action_minutes}` 자리표시자를 사용할 수 있습니다. 잘못된 자리표시자를 쓰면 서비스가 시작되지 않으므로 조용히 잘못 적용되지 않습니다.

### 학습 방식

한 답변에서 10개 축을 모두 바꾸면 어떤 변화가 편안함에 기여했는지 구분할 수 없습니다. 따라서 기본 설정은 매 답변에서 2개 축만 작은 범위로 시험합니다.

```ini
[style.learning]
learning_rate = 0.02
max_delta_per_run = 0.03
min_value = 0.05
max_value = 0.95
exploration_initial = 0.12
exploration_min = 0.025
exploration_decay_samples = 40
explored_dimensions_per_response = 2
```

흐름은 다음과 같습니다.

1. 현재 정서 구간의 개인 기준값 10개를 읽습니다.
2. 관찰 수가 적은 초기에는 2개 축을 최대 `±0.12` 범위에서 작게 시험합니다.
3. Android가 실제 적용값, 적용 전 기준값, 시험한 두 축을 상담 결과에 포함해 돌려보냅니다.
4. 답변 뒤 편안해졌으면 시험한 방향을 강화하고, 격양됐으면 반대 방향으로 이동합니다.
5. 수면 gate가 열린 야간 학습에서만 영구 반영하며 한 번에 축당 최대 `0.03`만 움직입니다.
6. 관찰이 쌓이면 탐색 폭은 점차 줄지만 `0.025`보다 작아지지 않습니다.

전략 순위와 스타일 값은 같은 정책 버전에 함께 저장되므로 롤백할 때 둘 다 복원됩니다. 기존 v1 데이터베이스는 시작 시 스타일 테이블과 버전 필드를 자동 추가하며, 기존 상담 결과와 토큰은 유지합니다.

`/v1/learning-status`의 `response_style_policy`에서 다음을 확인할 수 있습니다.

- `dimensions`: 스타일 축 수, 현재 `10`
- `observations`: 실제로 시험하고 학습에 사용한 축의 누적 관찰 수
- `changed_dimensions`: 초기값에서 실제로 달라진 고유 축 수
- `profiles`: 네 정서 구간별 현재 스타일 중심값

초기에는 전략과 마찬가지로 개인 관찰이 없으므로 설정한 기본값을 사용합니다. 최소 20개 이상의 상담 전후 결과가 쌓이기 전에는 스타일 값이 개인 취향이라고 단정하지 않는 것이 좋습니다.
