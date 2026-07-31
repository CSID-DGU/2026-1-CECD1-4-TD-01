# Jetson API 파이썬 제어 가이드

Jetson 내부에서 파이썬 스크립트를 사용하여 IoT 기기를 직접 제어하거나, 앱으로 푸시 알림을 전송하고, 데이터 동기화를 지시하는 방법을 설명합니다.

## 1. 커스텀 비상 알림 보내기 (`/v1/trigger-alert`)

딥러닝 모델(낙상 감지 등)이 특정 상황을 판단했을 때, 즉시 핸드폰 앱으로 알림을 보냅니다.

```python
import urllib.request
import json

def send_alert(title, message, severity="HIGH"):
    alert_data = {
        "title": title,
        "message": message,
        "severity": severity # INFO, CAUTION, HIGH, CRITICAL 중 하나
    }

    req = urllib.request.Request(
        "http://127.0.0.1:8765/v1/trigger-alert",
        data=json.dumps(alert_data).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-OnMom-Schema": "trigger-alert-v1"
        }
    )
    urllib.request.urlopen(req)

# 실행 예시
send_alert("할아버지 낙상 감지!", "거실에서 낙상이 의심됩니다.", "CRITICAL")
```

## 2. IoT 기기 강제 작동 (`/v1/iot/commands`)

홈 어시스턴트에 연동된 조명, 스위치 등의 엔티티를 파이썬 코드로 직접 켜고 끕니다.

```python
import urllib.request
import json

def control_iot_device(entity_id, turn_on=True):
    command_data = {
        "entity_id": entity_id,
        "command": "turn_on" if turn_on else "turn_off"
    }

    req = urllib.request.Request(
        "http://127.0.0.1:8765/v1/iot/commands",
        data=json.dumps(command_data).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-OnMom-Schema": "iot-command-v1"
        }
    )
    urllib.request.urlopen(req)

# 실행 예시 (거실 조명 켜기)
control_iot_device("light.living_room_light", turn_on=True)
```

## 3. 원본 데이터 동기화 백그라운드 요청 (SYNC_RAW_DATA)

앱에게 백그라운드에서 통화 기록, 앱 사용량, 건강 정보 등을 젯슨으로 수집하여 전송하라고 지시합니다. (사용자 폰에는 어떠한 알림도 울리지 않습니다)
이 스크립트를 Linux `cron`에 등록해 두면 하루에 한 번씩 주기적으로 데이터를 백업받을 수 있습니다.

```python
import urllib.request
import json

def request_raw_data_sync():
    # SYSTEM 카테고리와 특수 제목을 사용하면 앱이 이를 명령어로 해석합니다.
    alert_data = {
        "category": "SYSTEM",
        "title": "SYNC_RAW_DATA",
        "message": "requesting background sync",
        "severity": "INFO"
    }

    req = urllib.request.Request(
        "http://127.0.0.1:8765/v1/trigger-alert",
        data=json.dumps(alert_data).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-OnMom-Schema": "trigger-alert-v1"
        }
    )
    urllib.request.urlopen(req)

# 실행 예시
request_raw_data_sync()
```
