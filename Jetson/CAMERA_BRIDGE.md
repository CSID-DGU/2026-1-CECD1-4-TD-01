# Jetson 카메라 분석 브리지

`camera_analysis_bridge.py`는 Jetson에서 실행되는 자세·활동·얼굴 표정·rPPG 분석기의 고빈도 결과를 받아 1분 단위 `analysis-event-v1` 이벤트로 집계한다.

서비스를 `install_user_service.sh`로 설치하면 HTTP 서버와 함께 자동 실행된다.

```text
카메라 프레임
  -> 각 분석 모듈 내부에서 즉시 처리
  -> camera-sample-v1 파생 숫자
  -> udp://127.0.0.1:8766
  -> 60초 집계와 품질 계산
  -> SQLite Context Engine
```

UDP 포트는 loopback IPv4에만 바인딩된다. `0.0.0.0`이나 LAN 주소에는 바인딩할 수 없다.

## 허용 데이터

한 샘플은 다음 네 모달리티 중 하나 이상을 포함한다.

- `activity`: 사람 존재 여부, 움직임 점수, 신뢰도
- `posture`: `UPRIGHT`, `SLOUCH`, `AWAY`, `UNKNOWN`과 신뢰도
- `face_emotion`: valence, arousal, 신뢰도, 화면에서 검출된 얼굴 수
- `rppg`: 심박수, 신호 품질, 유효 측정 시간, 신뢰도

다음은 스키마에 존재하지 않아 거부된다.

- 프레임과 이미지 바이트
- 파일 경로와 URI
- 얼굴 landmark
- 얼굴 embedding 또는 신원
- base64 데이터
- 카메라 장치 식별 정보

얼굴이 정확히 한 명 검출된 샘플만 표정 집계에 사용한다. 0명 또는 여러 명이면 활동·자세·rPPG 같은 다른 모달리티는 유지하고 표정 값만 제외한다.

## Python 분석 모듈에서 보내기

각 분석 모듈에서 `send_camera_sample`을 불러온다.

```python
import time
import uuid

from camera_analysis_bridge import send_camera_sample

send_camera_sample(
    {
        "schema_version": 1,
        "sample_id": f"camera-{uuid.uuid4()}",
        "observed_at": int(time.time() * 1000),
        "observation_seconds": 1.0,
        "contains_raw_data": False,
        "activity": {
            "presence": True,
            "motion_score": 0.23,
            "confidence": 0.91
        },
        "posture": {
            "label": "UPRIGHT",
            "confidence": 0.86
        },
        "face_emotion": {
            "valence": -0.18,
            "arousal": 0.52,
            "confidence": 0.73,
            "face_count": 1
        },
        "rppg": {
            "heart_rate_bpm": 72.0,
            "signal_quality": 0.82,
            "measurement_seconds": 15.0,
            "confidence": 0.80
        }
    }
)
```

분석기가 초당 한 번 결과를 만들면 `observation_seconds`를 `1.0`으로 둔다. 분석기가 15초 rPPG 창 하나를 만들면 rPPG의 `measurement_seconds`는 `15.0`으로 둔다.

## 집계 결과

### 활동

- 유효 관측 초
- 사람이 관측된 시간 비율
- 움직임 점수 `0.12` 이상인 활동 시간 비율
- 평균 움직임 점수

### 자세

- 유효 관측 초
- 바른 자세·구부정·자리 비움 비율
- 바른 자세·구부정 시간

관측 시간이 60초 미만이면 Context Engine 중앙 품질 gate가 자세 결과를 `SUSPECT`로 바꿔 프롬프트와 학습에서 제외한다.

### 얼굴 표정

- 신뢰도로 가중한 valence `-1~1`
- 신뢰도로 가중한 arousal `0~1`
- 유효 단일 얼굴 관측 시간

15분 TTL을 가지며 `FACIAL_EXPRESSION` 도메인으로 저장된다. 사용자의 직접 표현이나 현재 메시지보다 우선하지 않는다.

### rPPG

- 창 안의 중앙 심박수
- 측정 시간으로 가중한 신호 품질
- 유효 측정 시간

다음 조건이면 중앙 품질 gate가 심박 값을 제외한다.

- `35~220 bpm` 범위 밖
- 신호 품질 `0.45` 미만
- 유효 측정 시간 10초 미만

Watch/Health Connect 심박과 동일한 `physiology.heart_rate` 증거 그룹을 사용하므로 프롬프트 신뢰도를 두 번 가산하지 않는다.

## 상태 확인

```bash
curl http://localhost:8765/health
```

`camera_bridge`에서 확인할 수 있는 값:

```json
{
  "running": true,
  "host": "127.0.0.1",
  "port": 8766,
  "buffered_samples": 12,
  "received_samples": 540,
  "duplicate_samples": 0,
  "rejected_samples": 0,
  "events_emitted": 28,
  "last_error": null,
  "accepts_raw_data": false
}
```

서비스 로그:

```bash
journalctl --user -u onmom-derived-insights.service -n 100 --no-pager
```

카메라 브리지만 끄고 서버를 직접 실행하려면:

```bash
python3 derived_insight_server.py --disable-camera-bridge
```

집계 창이나 포트를 바꾸려면:

```bash
python3 derived_insight_server.py \
  --camera-udp-port 8766 \
  --camera-window-seconds 60
```

## 테스트

저장소 루트에서:

```bash
python3 -m unittest -v Jetson/test_camera_analysis_bridge.py
```
