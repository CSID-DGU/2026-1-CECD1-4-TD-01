# On-mom 통합 분석 모듈 장기 설계

작성일: 2026-07-28  
대상: Android 상담 앱, Jetson, Home Assistant, RFID 출입 시스템, 자세/rPPG/음성 감정/Health Connect/Gallery/생활 로그 분석 모듈

## 1. 결론

이 프로젝트에서 먼저 학습시켜야 하는 것은 LLM 본체가 아니라 다음 세 가지다.

1. 어떤 센서 결과를 믿을지 결정하는 `신뢰도 가중치`
2. 사용자의 평소 상태와 오늘의 차이를 계산하는 `개인 기준선`
3. 현재 대화에서 어떤 맥락과 질문 전략을 선택할지 결정하는 `상담 정책 가중치`

LLM의 전체 가중치를 센서 로그가 들어올 때마다 수정하면 데이터 오류, 자기강화 오류, 망각, 복구 불가능 문제가 생긴다. 따라서 초기 제품에서는 기본 LLM을 고정하고 통합 분석 모듈이 만든 짧고 검증 가능한 맥락만 프롬프트에 넣는다.

충분한 사용자 피드백이 쌓인 뒤에도 먼저 작은 컨텍스트 랭커나 상담 전략 선택기의 가중치를 학습한다. LoRA 같은 LLM 어댑터 학습은 명시적으로 동의받아 선별한 대화-피드백 데이터가 충분할 때 오프라인으로 수행하고, 이전 버전으로 즉시 되돌릴 수 있게 한다.

## 2. 현재 코드에서 이어갈 수 있는 구조

현재 Android 앱에는 이미 다음 흐름이 있다.

```text
Health Connect ─┐
생활 패턴 ──────┼─> 분석 요약 ─> RAG 슬롯 ─> 최신 사용자 메시지의 프롬프트
Gallery 분석 ───┘

현재 음성 감정 ─> 짧은 TTL의 일회성 맥락 ────────────────┘

Android 파생 정보 ─> derived-only-v1 ─> Jetson
```

유지할 원칙:

- 원본 사진, 음성, 전화번호, 앱 이벤트 원문, 대화 원문은 Jetson과 LLM에 보내지 않는다.
- `0`, `기록 없음`, `센서 실패`, `실제 0`을 구분한다.
- 사용자의 직접 발화와 질문지 응답이 센서 추론보다 우선한다.
- 음성 감정처럼 빨리 낡는 정보는 만료 시간을 둔다.
- Gallery처럼 장기 경향을 보는 결과에는 분석 범위, 표본 수, 신뢰도, 경고를 포함한다.
- 분석 결과는 진단이 아니라 질문 방향을 정하는 보조 정보다.

바꿀 부분:

- 고정된 Health/Phenotype/Gallery 슬롯을 범용 `ContextCard` 규격으로 확장한다.
- Jetson을 단순 수신기에서 `통합 Context Engine`으로 확장한다.
- Android는 Context Engine에서 현재 대화용 묶음을 받아 프롬프트에 삽입한다.
- 기존 `derived-only-v1`은 호환 입력 어댑터로 유지한다.

## 3. 목표 아키텍처

```text
┌──────────────────── 데이터 생산 계층 ────────────────────┐
│ Android: Health/Gallery/통화·앱/음성 감정                  │
│ 자세 분석기: 프레임별 자세 -> 구간별 파생 통계             │
│ rPPG 분석기: 영상 -> 품질을 통과한 심박 구간               │
│ Home Assistant: RFID/재실/기기 상태 이벤트                 │
└────────────────────────┬──────────────────────────────────┘
                         │ 파생 이벤트만
                         v
┌──────────────────── Jetson Context Engine ────────────────┐
│ 1. Ingest Adapter       입력 변환, 인증, 스키마 검증       │
│ 2. Quality Gate         결측/0/범위/모순/중복/품질 판정    │
│ 3. Event Store          SQLite, 출처와 보존기한 기록       │
│ 4. Window Aggregator    30분/하루/7일/28일 특징 계산       │
│ 5. Baseline Engine      개인 기준선과 평소 대비 변화       │
│ 6. Fusion Engine        상관된 센서 중복 방지, 신뢰도 융합 │
│ 7. Context Ranker       현재 대화에 필요한 카드 선택       │
│ 8. Prompt Compiler      토큰 제한 내 안전한 프롬프트 생성  │
│ 9. Feedback Optimizer   사용자 피드백으로 정책 가중치 갱신 │
│ 10. Audit/Versioning    근거, 버전, 롤백 기록              │
└────────────────────────┬──────────────────────────────────┘
                         │ derived-only context bundle
                         v
┌──────────────────── Android On-device LLM ────────────────┐
│ 고정 안전 정책 + 사용자 기억 + 현재 대화 + 선택된 카드     │
│ -> 공감/확인 질문/작은 행동 제안                            │
└───────────────────────────────────────────────────────────┘
```

## 4. 데이터는 두 단계 규격으로 통일한다

### 4.1 AnalysisEvent

모듈 사이에서 이동하는 최소 단위다. 원본 프레임이나 로그 라인이 아니라 이미 파생된 관측값만 담는다.

```json
{
  "schema_version": "analysis-event-v1",
  "event_id": "uuid",
  "source": "home_assistant.rfid",
  "source_family": "presence.access",
  "event_type": "access_transition",
  "occurred_at": "2026-07-28T08:12:31+09:00",
  "received_at": "2026-07-28T08:12:32+09:00",
  "subject": "self",
  "values": {
    "direction": "ENTER",
    "zone": "front_door",
    "authorized": true
  },
  "quality": {
    "status": "VALID",
    "confidence": 1.0,
    "coverage": 1.0,
    "reasons": []
  },
  "privacy": {
    "contains_raw_media": false,
    "contains_direct_identifier": false,
    "retention_class": "SHORT"
  }
}
```

필수 원칙:

- RFID UID 대신 `self` 또는 로컬에서 만든 가명만 사용한다.
- 같은 사건의 재전송을 제거할 수 있도록 `event_id`를 안정적으로 만든다.
- 발생 시각과 수신 시각을 분리해 네트워크 지연과 과거 데이터 적재를 구분한다.
- 값이 없으면 `0`을 쓰지 않고 필드를 생략하거나 상태를 `MISSING`으로 둔다.
- `VALID`, `MISSING`, `SUSPECT`, `CONTRADICTED`, `EXPIRED` 상태를 구분한다.

### 4.2 ContextCard

시간 구간을 집계하고 개인 기준선과 비교한 뒤 AI에 제공할 수 있는 결과다.

```json
{
  "schema_version": "context-card-v1",
  "card_id": "uuid",
  "domain": "DAILY_ROUTINE",
  "evidence_group": "presence.access",
  "window": {
    "start": "2026-07-28T00:00:00+09:00",
    "end": "2026-07-29T00:00:00+09:00"
  },
  "generated_at": "2026-07-29T00:05:00+09:00",
  "expires_at": "2026-08-05T00:05:00+09:00",
  "observation": {
    "code": "ARRIVAL_LATER_THAN_BASELINE",
    "summary_ko": "최근 귀가 시각이 본인의 평소보다 늦어진 날이 이어졌습니다.",
    "current_value": 82,
    "baseline_value": 14,
    "unit": "minutes_delta"
  },
  "quality": {
    "confidence": 0.78,
    "coverage": 0.86,
    "sample_count": 6,
    "independent_evidence_count": 1,
    "warnings": [
      "출입 기록만으로 실제 재실 상태를 확정할 수 없음"
    ]
  },
  "prompt_policy": {
    "priority": "SUPPORTING",
    "allowed_use": "SOFT_CHECK_IN",
    "forbidden_claims": [
      "social_isolation",
      "sleep_disorder"
    ],
    "suggested_question_ko": "요즘 집에 돌아오는 시간이 조금 달라진 데 특별한 이유가 있었나요?"
  }
}
```

AI에 실제로 전달하는 최종 형태에서는 내부 source ID, UID, 세부 시각 배열, 경로를 제거한다.

## 5. 데이터 소스별 처리 정책

### 5.1 자세

프레임별 관절 좌표나 영상은 분석기 밖으로 내보내지 않는다.

파생 특징:

- 관측 가능 시간과 분석 실패 시간
- 바른 자세/구부정/고개 숙임/자리 비움의 시간 비율
- 연속된 좋지 않은 자세의 최장 구간
- 자세 전환 빈도
- 30분, 하루, 7일 집계
- 본인의 시간대별 28일 기준선 대비 변화

품질 조건:

- 신체 핵심 관절 가시성
- 카메라 각도와 거리
- 최소 유효 관측 시간
- 프레임 연속성
- 여러 사람이 보일 때 대상 불명확 처리

주의:

- 자세만으로 피로, 우울, 통증을 확정하지 않는다.
- 걸음 수가 많으면서 앉은 자세 시간이 긴 것은 자동 모순이 아니다. 서로 다른 시간 구간일 수 있다.
- 사용자가 실제 통증이나 불편을 말했을 때만 관련 확인 질문의 보조 근거로 쓴다.

### 5.2 rPPG 심박

카메라 영상과 얼굴 ROI는 저장하거나 전달하지 않는다.

파생 특징:

- 유효 구간별 중앙 심박수
- 안정 상태로 판정된 구간의 심박 범위
- 최근 구간과 개인 기준선의 차이
- 유효 구간 길이와 품질 점수

품질 조건:

- 얼굴 추적 성공률
- 움직임 크기
- 조명 안정성
- 신호 대 잡음비 또는 파형 품질
- 최소 연속 측정 시간
- 생리적으로 가능한 범위

융합 정책:

- 같은 시간대에 Watch/Health Connect 심박이 있으면 그것을 우선하고 rPPG를 보조 확인으로 쓴다.
- 같은 `physiology.heart_rate` 증거 그룹에 속한 값을 독립 증거 두 개처럼 더하지 않는다.
- Watch와 rPPG가 크게 다르면 평균내지 않고 `CONTRADICTED`로 표시한다.
- 검증되지 않은 rPPG 박동 간격으로 HRV를 추정해 상담 근거로 쓰지 않는다.
- 심박만으로 불안, 공황, 스트레스를 확정하지 않는다.

### 5.3 음성 감정

현재 구현처럼 현재 발화에만 가까운 짧은 TTL을 유지한다.

- 감정 라벨 하나뿐 아니라 분포, 최고 확률, 1·2위 확률 차이를 품질 계산에 쓴다.
- 낮은 신뢰도면 프롬프트에서 제외한다.
- 텍스트 내용과 충돌하면 사용자 발화를 우선한다.
- 장기 인격 특성이나 정신 상태로 저장하지 않는다.
- 여러 세션의 감정 라벨을 단순 누적해 “요즘 우울함”으로 결론내리지 않는다.

### 5.4 Health Connect

현재 구현된 0/극소값/범위/상호모순 필터를 공통 Quality Gate로 옮길 수 있다.

- Watch 심박, 걸음 수, 거리, 운동, 수면, 체성분 등은 출처별 원본을 그대로 프롬프트에 나열하지 않는다.
- 일·주 단위 유효 데이터 범위, 표본 수, 변화만 ContextCard로 만든다.
- 기록이 없는 날은 활동량 0일로 학습하지 않는다.
- 걸음 수와 거리처럼 논리적으로 연결된 항목의 모순은 제외 사유와 함께 감사 로그에 남긴다.
- rPPG와 겹치는 생리 정보는 증거 그룹을 공유한다.

### 5.5 Gallery와 앱/통화 패턴

기존 Gallery 모듈의 다음 구조를 공통 엔진에 재사용한다.

- recent window와 baseline window
- classification coverage, unknown ratio, average confidence
- Active Focus와 Supporting Focus의 분리
- 보호 자원과 작은 행동 후보의 분리
- 안전 신호와 일반 웰빙 신호의 분리

사진 수나 통화량 자체를 의미로 해석하지 않는다. 이벤트 반복성, 관측 기간, 데이터 커버리지와 본인 기준선 변화가 함께 있을 때만 확인 질문 후보로 사용한다.

### 5.6 Home Assistant와 RFID

AI에는 출입 로그 원문이 아니라 다음과 같은 일·주 단위 특징만 제공한다.

- 첫 외출/첫 귀가 시각의 평소 대비 변화
- 외출 구간 수와 관측 가능한 재실 시간
- 일상 시간대 규칙성
- 늦은 귀가 또는 장시간 외출의 연속 일수
- 출입 이벤트 데이터 커버리지
- 출입 기록과 다른 재실 센서 간 상충 여부

중요 제약:

- 리더가 하나이고 카드 태그 방향을 구분할 수 없으면 입실/퇴실을 확정할 수 없다.
- 방향이 다른 두 리더, 명시적 입/출 버튼, 도어 센서, 휴대폰 재실 정보 중 하나로 보강하는 것이 좋다.
- 같은 카드를 짧은 시간에 여러 번 찍으면 debounce로 중복 제거한다.
- 누락된 퇴실 뒤 다음 입실이 들어오면 임의로 재실 시간을 채우지 않고 해당 구간을 `UNKNOWN`으로 둔다.
- 출입 실패, 권한 거절 같은 보안 로그는 웰빙 프롬프트와 분리한다.
- “집에 오래 있음” 하나로 고립이나 우울을 추론하지 않는다.

## 6. Home Assistant 연결 방식

### 권장 조합

1. 실시간 이벤트: 로컬 MQTT
2. 누락 복구와 초기 과거 적재: Home Assistant REST History API
3. MQTT를 쓰지 않는 환경의 실시간 구독: Home Assistant WebSocket API

RFID 자동화가 아래와 같은 파생 이벤트를 MQTT에 발행하게 한다.

```text
topic: onmom/v1/ha/access
qos: 1
retain: false
```

```json
{
  "event_id": "ha-uuid",
  "occurred_at": "2026-07-28T08:12:31+09:00",
  "person_ref": "self",
  "direction": "ENTER",
  "zone": "front_door",
  "authorized": true,
  "reader_confidence": 1.0
}
```

보안:

- Mosquitto는 Home Assistant와 같은 사설망에 둔다.
- Jetson 전용 MQTT 계정을 만들고 읽을 topic을 `onmom/v1/ha/#`로 제한한다.
- Home Assistant 장기 토큰은 꼭 필요할 때만 Jetson의 권한 `0600` 설정 파일에 둔다.
- 전체 event bus나 전체 logbook을 가져오지 말고 허용 목록의 entity/event만 수집한다.
- 카드 UID와 사용자 실명은 payload에 넣지 않는다.

공식 참고:

- Home Assistant REST API: https://developers.home-assistant.io/docs/api/rest/
- Home Assistant WebSocket API: https://developers.home-assistant.io/docs/api/websocket/
- Home Assistant MQTT: https://www.home-assistant.io/integrations/mqtt/

## 7. 품질, 결측, 모순 처리

모든 센서 값은 숫자와 별도로 상태를 가진다.

```text
VALID        계산과 프롬프트 후보에 사용
MISSING      기록 또는 권한이 없음, 0으로 바꾸지 않음
SUSPECT      범위는 맞지만 품질/표본이 부족함
CONTRADICTED 다른 신뢰 가능한 정보와 충돌함
EXPIRED      현재 맥락으로 쓰기에는 오래됨
EXCLUDED     개인정보 또는 정책상 사용 금지
```

유효 신뢰도 예시:

```text
effective_confidence
  = source_reliability
  × measurement_quality
  × coverage
  × freshness
  × consistency
  × support
```

```text
freshness = exp(-age / domain_ttl)
support   = min(1, log(1 + valid_samples) / log(1 + target_samples))
```

각 항은 0~1로 제한한다. 단, 실제 구현에서는 낮은 항 하나를 곱셈으로 숨기지 않도록 원래 항목도 ContextCard와 감사 화면에 함께 남긴다.

상관 센서 처리:

- Watch HR와 rPPG HR는 같은 증거 그룹이므로 더해서 확신을 두 배로 만들지 않는다.
- RFID와 휴대폰 재실은 별도 증거 그룹이지만 같은 생활 현상을 측정하므로 상충 검사를 먼저 한다.
- 자세와 Health Connect 활동은 관측 구간이 겹칠 때만 상충 여부를 판단한다.
- 직접 사용자 발화는 센서 카드보다 우선하며, 센서와 다르면 센서 가중치를 낮추고 사용자에게 재확인을 강요하지 않는다.

높은 우선순위 신호 조건:

- 충분한 coverage와 표본
- 개인 기준선 대비 의미 있는 변화
- 서로 독립적인 증거 두 종류 또는 사용자의 직접 확인
- 최근성
- 현재 대화 주제와의 관련성

위 조건이 없으면 Supporting 또는 숨김 상태로 둔다.

## 8. 개인 기준선

집단의 정상 수치보다 사용자의 평소 패턴을 우선한다.

### 초기 보정 기간

- 최초 14일은 `CALIBRATING` 상태로 둔다.
- 이 기간에는 명백한 안전 규칙 외에 강한 변화 문구를 만들지 않는다.
- 최소 유효 일수에 못 미치면 기간이 지났어도 보정을 끝내지 않는다.

### 기준선 계산

- 기본 창: 최근 28일
- 비교 창: 최근 1일, 3일, 7일
- 시간대와 요일 영향을 받는 값은 같은 요일/시간대끼리 비교
- 중앙값과 MAD 기반의 robust z-score 사용
- 급격한 이상값이 기준선을 따라 움직이지 않도록 업데이트 변화량을 제한
- 사용자가 “여행”, “시험 기간”, “아픈 날”처럼 예외로 표시한 기간은 기준선 학습에서 제외 가능

```text
robust_z = 0.6745 × (current - median_baseline) / MAD
```

MAD가 0이거나 표본이 부족하면 z-score를 만들지 않고 절대 변화와 coverage만 표시한다.

기준선은 분석용이며 “정상/비정상” 라벨을 만들지 않는다.

## 9. 여러 결과를 하나의 상담 맥락으로 합치는 방법

### 9.1 도메인

초기 공통 도메인은 다음 정도로 제한한다.

- `SLEEP_AND_ROUTINE`
- `PHYSICAL_ACTIVITY`
- `SEDENTARY_AND_POSTURE`
- `SOCIAL_CONNECTION`
- `SCHOOL_OR_WORK_LOAD`
- `RECOVERY_RESOURCE`
- `MEAL_ROUTINE`
- `PHYSIOLOGICAL_STATE`
- `DAILY_ROUTINE`
- `GENERAL_CHECK_IN`

### 9.2 융합 점수

각 카드의 방향 점수 `signal`을 -1~1, 유효 신뢰도를 0~1로 두면 도메인 점수는 다음과 같이 계산할 수 있다.

```text
domain_score = Σ(effective_confidence_i × signal_i)
               / Σ(|effective_confidence_i|)
```

그러나 점수 하나만으로 Active Focus를 만들지 않는다. 다음 gate를 함께 통과해야 한다.

- 데이터 품질 gate
- 개인 기준선 gate
- 독립 증거 수 gate
- 대화 관련성 gate
- 금지 추론 gate
- 사용자 동의 gate

### 9.3 모순 결과

모순된 값을 억지로 평균내지 않는다.

```text
Watch HR: 72 bpm, high quality
rPPG HR: 118 bpm, low motion quality
=> Watch를 사용하고 rPPG는 SUSPECT

RFID: ENTER 뒤 EXIT 없음
휴대폰 presence: away
=> 해당 재실 구간 UNKNOWN, home duration 계산 제외
```

모순 자체는 센서 상태 개선에 쓰되, 상담 프롬프트에는 보통 넣지 않는다.

## 10. 프롬프트 반영

### 10.1 프롬프트 우선순위

```text
1. 고정 안전·상담 정책
2. 현재 사용자 메시지
3. 사용자가 직접 저장한 중요한 기억
4. 현재 대화와 관련된 Active ContextCard 최대 1개
5. Supporting ContextCard 최대 2개
6. 관련 과거 대화 기억
```

센서 맥락 때문에 현재 사용자의 말을 뒤로 밀면 안 된다.

### 10.2 카드 선택 점수

```text
selection_score
  = confidence
  × freshness
  × conversation_relevance
  × novelty
  × actionability
```

추가 규칙:

- 같은 evidence group에서는 가장 신뢰할 수 있는 카드 하나만 우선한다.
- 현재 대화와 관계가 없으면 높은 변화라도 넣지 않는다.
- Active Focus는 한 번에 하나만 사용한다.
- Supporting은 사용자가 관련 주제를 먼저 말했을 때 후속 질문에 쓴다.
- 토큰 예산을 넘으면 신뢰도가 아니라 관련성이 낮은 카드부터 제거한다.

### 10.3 LLM에 들어가는 예시

```text
[통합 생활 맥락 v1]
용도: 진단이 아니라 사용자의 경험을 확인할 질문 방향을 정하는 보조 정보.

ACTIVE
- 최근 7일 동안 평소보다 귀가 시간이 늦어진 날이 이어졌음.
  신뢰도: 중간(0.78), 유효 6일/7일.
  한계: 출입 기록만으로 실제 생활 상태를 확정할 수 없음.
  사용: 부드러운 확인 질문 1개.

SUPPORTING
- 최근 3일의 유효 자세 관측에서 연속적인 구부정 자세 시간이 개인 기준선보다 늘어남.
  신뢰도: 중간(0.71). 사용자가 피로나 불편을 말할 때만 후속 질문.

금지:
- 우울, 불안, 고립, 수면 장애를 센서만으로 확정하지 말 것.
- 센서 종류나 감시 사실을 먼저 나열하지 말 것.
- 사용자 설명이 다르면 사용자 설명을 우선할 것.
```

예상 응답:

```text
요즘 하루 일정이 평소와 조금 달라진 것 같아요. 최근에 집에 돌아오는 시간이 늦어진 데 특별한 이유가 있었나요?
```

## 11. “스스로 개선”의 실제 구현

### 단계 A: 기준선과 품질 규칙의 자동 적응

모델 학습 없이도 개인화 효과가 가장 크다.

- 센서별 실제 성공률로 `source_reliability` 보정
- 사용자가 잘못된 카드라고 표시하면 해당 조건의 신뢰도 감소
- 예외 기간은 기준선 갱신에서 제외
- 자주 상충하는 소스는 자동으로 Supporting 이하로 제한

### 단계 B: 작은 Context Ranker 학습

Jetson에서 다음 입력으로 로지스틱 회귀, 작은 MLP 또는 contextual bandit을 학습한다.

입력:

- 카드 도메인
- 신뢰도와 coverage
- 최근성
- 대화 주제 임베딩 또는 분류
- 현재 상담 단계
- 이전에 같은 카드가 사용된 횟수
- 사용자가 이 종류의 맥락을 허용했는지

출력:

- 이번 응답에서 숨김/Supporting/Active 중 무엇으로 둘지
- 반영, 개방 질문, 명료화, 작은 행동 제안 중 어떤 전략을 우선할지

피드백:

- 명시적: 도움 됨/아님, 맥락 맞음/틀림, 너무 부담스러움
- 수정: 오늘은 예외, 이 데이터 사용 안 함, 센서 오류
- 제한된 암시적 신호: 질문에 답했는지, 해당 맥락을 사용자가 확인했는지

최적화하면 안 되는 것:

- 앱 체류 시간
- 대화 길이 자체
- 사용자의 심박 감소
- 센서가 예측한 감정과 AI 응답의 일치율
- AI가 스스로 만든 답변을 정답으로 다시 학습하는 것

정책 업데이트 예시:

```text
w_next = clip(
  w_current + learning_rate × (observed_reward - predicted_reward) × features,
  min_weight,
  max_weight
)
```

안전 정책, 진단 금지, 개인정보 정책의 가중치는 학습 대상이 아니다.

### 단계 C: LoRA 어댑터

다음 조건이 충족된 뒤에만 실험한다.

- 명시적 학습 동의
- 사용자가 삭제할 수 있는 데이터셋
- 사람이 검토한 prompt-response-feedback 쌍
- 시간 순서로 분리한 train/eval 데이터
- 기존 모델보다 나아졌음을 보이는 고정 평가 세트
- 안전 회귀 테스트 통과
- 어댑터 버전 서명, 배포 중단, 즉시 롤백 기능

한 사용자의 적은 데이터로 LLM LoRA를 계속 업데이트하는 것은 과적합 가능성이 크다. 초기에 얻고 싶은 개인화는 대부분 RAG, 개인 기준선, Context Ranker로 구현하는 편이 낫다.

현재 MediaPipe LLM Inference API는 유지보수 모드이며 Google은 신규 기능에 LiteRT-LM 사용을 권장한다. 향후 LLM 실행 계층을 교체할 때는 LiteRT-LM을 우선 검토한다.

- LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM
- MediaPipe LLM Inference/LoRA: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference

## 12. 피드백 UI

AI 답변 아래에 항상 평점을 강요하지 않고 작은 선택지를 둔다.

- 이 답변이 도움이 됐어요
- 맥락은 맞지만 질문이 부담스러워요
- 사용한 맥락이 틀렸어요
- 오늘은 예외적인 날이에요
- 이 종류의 데이터는 상담에 사용하지 않기

분석 상세 화면:

- AI가 사용한 카드
- 사용하지 않은 카드와 이유
- 신뢰도와 관측 범위
- 제외된 데이터 수와 이유
- 개인 기준선 기간
- 정책/모델 버전
- 데이터 삭제와 학습 제외

“왜 이런 질문을 했나요?”를 누르면 원본 로그가 아니라 사용된 카드와 한계를 보여준다.

## 13. Jetson 저장 구조

초기에는 PostgreSQL보다 SQLite WAL 모드가 충분하다.

```text
events
  event_id, schema_version, source, source_family,
  occurred_at, received_at, payload_json,
  quality_status, retention_until

features
  feature_id, domain, window_start, window_end,
  value, unit, coverage, sample_count, evidence_group

baselines
  subject, feature_code, segment_key,
  median, mad, valid_days, version, updated_at

context_cards
  card_id, domain, generated_at, expires_at,
  card_json, status, policy_version

feedback
  feedback_id, response_id, card_id,
  feedback_type, value, created_at

policy_versions
  version, weights_json, evaluation_json,
  created_at, active, rollback_parent

audit
  trace_id, stage, decision, reasons_json, created_at
```

보존 기간 기본안:

- 원본을 포함하지 않는 세부 AnalysisEvent: 30일
- 시간 집계 Feature: 90일
- 개인 기준선: 최신 버전 + 이전 5개
- ContextCard: 90일
- 피드백과 정책 버전: 사용자가 삭제하기 전까지
- 음성 감정: 현재 구현처럼 짧은 TTL

보존 기간은 앱에서 사용자가 줄이거나 전체 삭제할 수 있게 한다.

## 14. Jetson API 초안

```text
POST /v1/analysis-events
  자세/rPPG/HA 등 표준 파생 이벤트 입력

POST /v1/derived-insights
  기존 Android derived-only-v1 호환 입력

GET /v1/context-bundle
  Android가 현재 대화 주제와 단계에 맞는 카드 묶음 요청

POST /v1/feedback
  답변 및 카드 사용에 대한 사용자 피드백

GET /v1/audit/context-cards
  개발자/사용자 확인 화면

GET /health
  schema, DB, MQTT, HA, last_event 상태
```

`GET /v1/context-bundle` 요청에는 대화 원문 전체 대신 우선 로컬에서 만든 주제 코드와 상담 단계만 보내는 방식을 먼저 사용한다.

```json
{
  "conversation_phase": "RAPPORT",
  "topic_codes": ["DAILY_ROUTINE", "SLEEP_AND_ROUTINE"],
  "max_cards": 3,
  "max_prompt_chars": 3500
}
```

## 15. 권장 코드 구조

```text
Analysis/IntegratedContext/
  ARCHITECTURE_PLAN.md
  schemas/
    analysis-event-v1.schema.json
    context-card-v1.schema.json
    feedback-v1.schema.json
  context_engine/
    config.py
    db.py
    ingest/
      android_adapter.py
      ha_mqtt_adapter.py
      ha_history_adapter.py
      posture_adapter.py
      rppg_adapter.py
    quality/
      common_gate.py
      contradiction.py
      source_policies.py
    features/
      window_aggregator.py
      presence_features.py
      physiology_features.py
      posture_features.py
    baseline/
      robust_baseline.py
    fusion/
      domain_fusion.py
      context_ranker.py
    prompt/
      context_bundle.py
      prompt_compiler.py
    learning/
      feedback_store.py
      policy_optimizer.py
      policy_registry.py
    api/
      server.py
  tests/
    fixtures/
    test_quality.py
    test_presence_state_machine.py
    test_baseline.py
    test_fusion.py
    test_prompt_privacy.py
    test_policy_rollback.py
```

Android:

```text
Frontend/app/src/main/java/com/example/counseling/context/
  ContextBundleClient.kt
  ContextCardModels.kt
  ContextPromptCompiler.kt
  ContextConsentStore.kt
  ContextFeedbackStore.kt
  ContextAuditScreen.kt
```

## 16. 18주 구현 로드맵

### 0단계 — 데이터 계약과 개인정보 범위, 1주

작업:

- 모든 데이터 소스와 실제 필드 목록 작성
- 원본/파생/직접 식별자 분류
- AnalysisEvent/ContextCard JSON Schema 확정
- 보존 기간과 사용자 동의 단위 확정
- 공통 품질 상태와 evidence group 목록 확정

완료 조건:

- 모든 모듈의 예제 payload가 schema 검증을 통과
- 금지 필드가 들어오면 Jetson과 Android 양쪽에서 거부

### 1단계 — Context Engine 뼈대, 2주

작업:

- SQLite 저장소와 마이그레이션
- 인증, 중복 제거, 크기 제한
- `/v1/analysis-events`, `/health`, audit API
- 기존 derived-only-v1 호환 어댑터
- 이벤트 보존 기간 정리 작업

완료 조건:

- 재시작 후 데이터와 정책 버전 보존
- 같은 event_id 재전송 시 한 번만 저장
- 원본 미디어/URI/전화번호/카드 UID 거부 테스트 통과

### 2단계 — Home Assistant/RFID 세로 연결, 2주

작업:

- Mosquitto topic과 Jetson 계정 설정
- HA RFID 자동화에서 파생 이벤트 발행
- Jetson MQTT 구독 및 재연결
- 출입 상태 머신, debounce, 누락/모순 처리
- REST History를 이용한 제한적 backfill
- `DAILY_ROUTINE` ContextCard 생성

완료 조건:

- 태그 1회가 이벤트 1개로 저장
- HA/Jetson 재시작 뒤 자동 복구
- 방향 불명/누락 구간은 home duration에서 제외
- 카드 UID가 DB와 프롬프트에 없음

### 3단계 — 자세와 rPPG 어댑터, 2주

작업:

- 자세 구간 요약 규격과 품질 gate
- rPPG 구간 요약 규격과 품질 gate
- Watch HR 중복/상충 처리
- source health 지표와 실패 사유 화면

완료 조건:

- 저조도/움직임/얼굴 소실 rPPG가 프롬프트에서 제외
- 자세 미관측 시간이 바른 자세나 나쁜 자세 0으로 계산되지 않음
- Watch/rPPG 충돌 시 임의 평균 금지

### 4단계 — 개인 기준선과 융합, 2주

작업:

- 14일 보정 상태
- 28일 median/MAD 기준선
- 요일/시간대 segmentation
- 도메인 점수와 독립 증거 gate
- Active 1개, Supporting 최대 2개 정책

완료 조건:

- 표본이 부족하면 강한 카드가 생성되지 않음
- 동일한 심박 신호가 두 번 더해지지 않음
- 예외 기간을 기준선에서 제외 가능

### 5단계 — Android 프롬프트 통합, 2주

작업:

- Context Bundle 조회와 로컬 캐시
- 기존 RAG 슬롯을 카드로 변환
- 프롬프트 토큰 예산과 만료 처리
- 분석 상세/사용 여부/삭제 UI
- Jetson 연결 실패 시 현재 로컬 RAG로 안전하게 fallback

완료 조건:

- 현재 사용자 메시지가 항상 센서 맥락보다 우선
- 만료 카드가 들어가지 않음
- 네트워크가 없어도 기본 상담 가능
- 사용자가 도메인별 사용을 끌 수 있음

### 6단계 — Shadow mode와 평가, 2주

작업:

- 카드는 만들지만 1~2주간 LLM에는 넣지 않고 예상 선택만 기록
- 과거 이벤트 replay 시나리오
- 잘못된 Active 선택, 모순, 누락률 측정
- 프롬프트 전/후 응답을 고정 평가 세트로 비교

완료 조건:

- privacy leak 0
- expired/invalid 카드 선택 0
- Active Focus 규칙 위반 0
- 시나리오별 예상 질문 도메인과 일치
- false alarm 검토 완료

### 7단계 — 피드백과 정책 가중치 학습, 2주

작업:

- 답변 피드백 UI
- Context Ranker의 제한된 온라인 업데이트
- 최소/최대 가중치와 작은 learning rate
- 정책 버전, 평가, 자동 롤백
- 새 정책은 shadow/canary 후 활성화

완료 조건:

- 피드백 한 건이 안전 정책을 변경하지 못함
- 이전 정책으로 즉시 롤백 가능
- 잘못된 맥락 피드백 뒤 같은 상황의 선택 확률이 의도대로 감소

### 8단계 — 선택적 LoRA 연구, 3주 이상

작업:

- 데이터 충분성 및 동의 검토
- 학습셋/시간 분리 평가셋 구성
- Jetson에서 오프라인 학습 가능성 벤치마크
- LiteRT-LM 배포 가능 모델과 어댑터 호환 확인
- 안전·상담 품질·지연·메모리 회귀 테스트

완료 조건:

- 기본 모델과 정책 랭커보다 유의미하게 나음
- 안전 회귀 없음
- 배터리/발열/메모리 허용 범위
- 서명된 어댑터와 원클릭 롤백

조건을 만족하지 못하면 LoRA를 배포하지 않는다.

## 17. 평가 지표

데이터:

- source별 수신 성공률
- 유효 coverage
- MISSING/SUSPECT/CONTRADICTED 비율
- 중복 제거율
- 이벤트 지연
- 기준선에 사용된 유효 일수

융합:

- 독립 증거 수
- 신뢰도 calibration
- 잘못된 Active Focus 비율
- 모순을 평균낸 사례 수
- 같은 evidence group 중복 가산 수

프롬프트:

- 평균/최대 컨텍스트 토큰
- 만료 카드 포함 수
- 현재 대화와 무관한 카드 비율
- 개인정보 금지 문자열 검출 수
- 사용자 직접 발화와 충돌한 주장 수

사용자 경험:

- 맥락이 맞았다는 명시적 비율
- 도움이 됐다는 비율
- 부담스럽다는 비율
- 사용자가 수정/제외한 데이터 종류
- 질문 이후 사용자가 해당 주제를 확인 또는 부정한 비율

안전:

- 센서만으로 진단한 응답 수
- 이미지/감정/RFID만으로 위기 확정한 응답 수
- 직접 위기 발화에서 고정 안전 정책 누락 수
- 정책 버전별 회귀 테스트 결과

## 18. 첫 번째로 구현할 수직 기능

첫 구현은 아래 하나로 제한하는 것이 좋다.

```text
Home Assistant RFID
  -> MQTT access event
  -> Jetson schema/중복/상태 머신
  -> SQLite
  -> 하루 DAILY_ROUTINE ContextCard
  -> Android 분석 상세 화면
  -> Shadow mode
  -> 사용자 확인 뒤 프롬프트에 Active 또는 Supporting으로 사용
```

이 순서를 권하는 이유:

- 이미 실제 RFID 시스템과 로그가 있다.
- 이벤트가 명확해 ingest, 저장, 품질, 기준선, 프롬프트의 전체 구조를 검증하기 좋다.
- 영상/생리 데이터보다 품질 gate를 설명하기 쉽다.
- 이 세로 연결이 완성되면 자세와 rPPG는 같은 AnalysisEvent/ContextCard 규격에 어댑터만 추가하면 된다.

단, RFID 리더가 방향을 구분하지 못한다면 첫 버전의 목표를 `출입/태그 시각 규칙성`으로 낮추고 `재실 시간`은 계산하지 않는다.

## 19. 지금 결정한 원칙

1. 원본 로그를 프롬프트에 붙이지 않는다.
2. 원본 미디어와 직접 식별자는 분석 모듈 경계를 넘기지 않는다.
3. 결측을 0으로 바꾸지 않는다.
4. 센서마다 품질과 TTL을 둔다.
5. 같은 현상을 측정한 센서는 독립 증거처럼 중복 가산하지 않는다.
6. 개인 기준선은 충분한 유효 데이터가 쌓인 뒤 사용한다.
7. Active Focus는 한 번에 하나만 선택한다.
8. 사용자의 직접 발화가 센서 추론보다 우선한다.
9. 초기 자기개선 대상은 Context Ranker와 정책 가중치다.
10. 안전·진단 금지·개인정보 정책은 학습으로 변경하지 않는다.
11. 모든 정책과 모델은 버전 관리하고 롤백할 수 있어야 한다.
12. LoRA는 필수가 아니라 충분한 데이터와 평가가 있을 때의 마지막 선택지다.

