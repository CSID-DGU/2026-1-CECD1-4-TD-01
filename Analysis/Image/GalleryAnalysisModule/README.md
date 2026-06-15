# GalleryAnalysisModule CounselingSafety v3.4 WHO-aligned Audit

이 버전은 기존 활동 카테고리 결과를 그대로 상담에 넘기지 않고, WHO 청소년 건강 권고와 맞는 상담 도메인 proxy로 재해석합니다.

## 핵심 변경

1. WHO 정렬 상담 도메인 추가
   - SLEEP_AND_ROUTINE
   - PHYSICAL_ACTIVITY
   - SEDENTARY_PATTERN
   - SOCIAL_CONNECTION
   - SCHOOL_STRESS
   - RECOVERY_RESOURCE
   - MEAL_ROUTINE
   - SAFETY_AND_VIOLENCE
   - GENERAL_CHECK_IN

2. 활동 카테고리 → 상담 도메인 변환
   - 축구/운동/야외 → 신체활동/외출
   - 00~04시 이벤트 증가 → 수면/생활리듬 확인
   - 사회/다인/얼굴 맥락 감소 → 사회적 연결 변화 확인
   - 학업 이미지는 야간/생활 리듬/활동 저하와 함께 나타날 때만 강하게 반영
   - 취미/반려동물/자연은 회복 자원 후보로 분리

3. baseline zero 과잉 HIGH 완화
   - recent 14일과 baseline 30일 비율에 smoothing 적용
   - event support가 부족하면 confidence를 낮춤
   - 학업/식사/좌식은 단독 HIGH 방지

4. Audit mode 추가
   - per_image_audit.csv
   - per_event_audit.csv
   - scenario_summary.json
   - 원본 이미지는 저장하지 않음
   - 저장 위치: 앱 전용 외부 저장소의 GalleryAnalysisAudit/run_<timestamp>

5. LLM prompt 재정렬
   - raw label 나열 금지
   - WHO-ALIGNED WELLBEING DOMAINS
   - PRIMARY COUNSELING FOCUS 최대 2개
   - SECONDARY SIGNALS 분리
   - PROTECTIVE RESOURCES
   - MICRO ACTION PLAN은 후반부에만 사용

## 테스트 기준

A_NormalBalanced:
- HIGH domain 0개 또는 GENERAL_CHECK_IN
- safety NONE

B_AcademicNightIncrease:
- SLEEP_AND_ROUTINE HIGH
- SCHOOL_STRESS는 수면/야간 학업과 결합될 때 primary 가능

C_SocialDecrease:
- SOCIAL_CONNECTION HIGH

D_BehaviorActivationDrop:
- PHYSICAL_ACTIVITY HIGH
- safety false alarm 없어야 함

E_InterestLossCandidate:
- RECOVERY_RESOURCE 감소 또는 PHYSICAL_ACTIVITY 감소가 primary

F_SafeRiskCueOnly:
- 이미지 단서만으로 HIGH_SUPPORT/IMMEDIATE_DANGER 금지
- SCHOOL_STRESS 과잉 HIGH 금지

## 주의

이 모듈은 진단 모델이 아닙니다. 결과는 상담 질문의 방향을 정하는 보조 정보이며, 사용자의 직접 발화와 질문지 응답을 항상 우선합니다.


## v3.4.5 Prompt Usability 개선

- AI 상담 프롬프트를 `COUNSELING BRIEF - READ FIRST`, `USE NOW`, `USE LATER`, `PREFERENCE / RECOVERY RESOURCES`, `MICRO ACTION PLAN - FINAL PHASE ONLY`로 재구성했습니다.
- 라포 단계에서는 생활 개선 제안을 금지하고, 사용자 경험 확인 질문 1개만 생성하도록 명시했습니다.
- 반복성과 주기성이 높은 선호/회복 자원은 초반 해결책이 아니라, 문제 탐색 이후 “도움이 되는지 확인하는 질문”으로만 사용하도록 분리했습니다.
- 화면 출력도 사람용 요약과 LLM 입력용 프롬프트, 디버그 상세를 분리했습니다.
- 평균 신뢰도/분류 커버리지/보정 의존도에 대한 경고를 더 눈에 띄게 표시합니다.


## v3.4.5 최종 프롬프트 보완
- Active Focus와 Supporting Focus를 분리했습니다. LLM은 라포 단계에서 Active Focus 1개만 질문합니다.
- Supporting Focus는 사용자가 관련 경험을 말한 뒤에만 사용하도록 프롬프트를 명시했습니다.
- 보정률이 높은 경우 HIGH_CORRECTION_DEPENDENCY 경고가 AI 프롬프트 내부에도 포함됩니다.
- 선호/회복 자원 질문 문장을 더 완곡하게 조정했습니다.
- 조사 오류를 보정했습니다.


## v3.4.5 패치
- SLEEP/SCHOOL 점수에 delta 방향성 gate 적용
- Active Focus 1개, Supporting Focus 최대 1개로 고정
- RECOVERY_RESOURCE가 주요 도메인을 밀어내지 않도록 후반부 자원 블록으로 이동
- MEAL_ROUTINE은 시간대/루틴 변화가 있을 때만 MEDIUM 이상 허용


## v3.4.5 Safety Overlay 패치

이번 버전은 안전 시각 단서가 수면/학업/활동 도메인과 경쟁하다가 묻히는 문제를 줄였습니다.

- `VisualRiskCueDetector`를 강화해 상처/붕대/부상/신체 클로즈업 조합을 안전 확인 보조 단서로 탐지합니다.
- 이미지 단독 안전 단서는 최대 `MEDIUM_CHECK_IN`까지만 허용합니다. `HIGH_SUPPORT`와 `IMMEDIATE_DANGER`는 직접 발화/질문지 같은 명시적 위험 신호가 있을 때만 사용합니다.
- `Safety Overlay`를 LLM 프롬프트에 별도 표시합니다.
- `PHYSICAL_ACTIVITY` Active threshold를 높여 정상 기준선에서 작은 활동 감소가 과잉 Active로 뜨는 문제를 완화했습니다.
- `SCHOOL_STRESS`는 야간 증가가 없어도 학업/과제 증가가 충분하면 Active 후보가 될 수 있도록 recall을 보강했습니다.

권장 테스트 순서: A → B → C → D → E. 특히 E에서는 `Safety = LOW_OBSERVE` 또는 `MEDIUM_CHECK_IN`, `HIGH_SUPPORT 금지`, `이미지 단독 확정 금지`가 기대값입니다.
