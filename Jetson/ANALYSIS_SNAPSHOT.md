# 개발자 구조화 분석 스냅샷

`analysis-snapshot-v1`은 상담용 파생 요약과 별개로, 개발·통계 분석에
사용할 숫자와 범주형 결과를 Android에서 Jetson으로 수동 전송하는
채널이다.

## 개인정보 경계

허용되는 자료:

- 날짜별 유효 건강값과 제외 사유
- 운동 횟수·시간·종류별 횟수
- 수면 단계 시간과 생체정보 통계
- 통화량·앱 사용 시간 같은 피노타입 집계
- Gallery 분석의 개수·비율·점수·신뢰도·기간 변화

허용되지 않는 자료:

- 사진·영상·음성 바이트
- 사진 URI와 파일 경로
- 전화번호·연락처 이름·RFID UID
- Android 패키지 이름
- 통화·대화·상담 원문
- 얼굴 특징점·임베딩과 센서 원파형

Android와 Jetson 양쪽에서 금지 표식과 필드명을 검사한다. 한 요청에
금지 자료가 하나라도 있으면 전체 스냅샷을 거부한다.

## Android에서 보내기

개발자 화면의 `Jetson 파생 정보 전송` 창에서 주소와 토큰을 입력한 뒤
`개발자 분석 수치만 수동 전송`을 누른다. 상담 중 자동으로 보내는
파생 요약과 달리 분석 스냅샷은 수동 동작으로만 전송된다.

현재 데이터셋:

| category | 내용 |
|---|---|
| `HEALTH` | 주간 합계, 날짜별 유효값, 제외 사유, 확장 건강·운동 수치 |
| `PHENOTYPE` | 통화 패턴 집계, 스크린타임, 야간 사용, 앱별 표시 이름·분류·시간 |
| `GALLERY` | 분석 품질, 활동별 빈도, 기간 변화, 웰빙 점수, 보호 자원 |

기존 Gallery 캐시는 관심사 수치만 보낼 수 있다. Gallery 화면에서
분석을 한 번 갱신하면 전체 구조화 분석 결과가 캐시에 추가된다.

## Jetson에서 조회

최근 스냅샷:

```bash
cd ~/Jetson
python3 onmom_client.py analysis-snapshots --limit 10
```

카테고리 필터:

```bash
python3 onmom_client.py analysis-snapshots --category HEALTH --limit 5
python3 onmom_client.py analysis-snapshots --category PHENOTYPE --limit 5
python3 onmom_client.py analysis-snapshots --category GALLERY --limit 5
```

HTTP API:

```text
POST /v1/analysis-snapshots
X-OnMom-Schema: analysis-snapshot-v1

GET /v1/analysis-snapshots?limit=10&category=HEALTH
```

두 요청 모두 기존 Bearer 토큰 인증을 사용한다.

## 저장과 보존

스냅샷은 다음 SQLite 테이블에 저장된다.

```text
~/Jetson/data/onmom_context.db
table: analysis_snapshots
```

상담 카드 생성과 개인화 학습에서는 이 테이블을 읽지 않는다. 기본
보존 기간은 30일이며 `adaptive_policy.ini`의 다음 값을 수정할 수 있다.

```ini
[retention]
analysis_snapshots_days = 30
```

변경 후 테스트와 서비스 재시작:

```bash
cd ~/Jetson
python3 -m unittest discover -s . -p "test_*.py"
systemctl --user restart onmom-derived-insights.service
```
