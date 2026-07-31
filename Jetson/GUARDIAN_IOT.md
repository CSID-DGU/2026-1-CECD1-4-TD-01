# 보호자 위험 알림과 IoT 제어

## 앱 역할

- 첫 실행에서 `사용자` 또는 `보호자`를 고른다.
- 선택은 앱 설정에 저장되며 언제든 바꿀 수 있다.
- 어느 역할에서든 설정의 버전 항목을 5회 누르면 개발자 화면으로 전환된다.
- 사용자와 개발자는 기존 화면을 유지하며 `IoT` 탭이 추가된다.
- 보호자는 `안심`과 `IoT` 탭을 사용한다.

보호자 모드는 Android의 연결된 기기 foreground service로 Jetson을 15초마다 확인한다.
위험 알림 권한과 지속적인 Jetson LAN 연결이 필요하다.

## 위험 알림 API

분석 모듈 또는 Home Assistant는 원본 없이 다음 API로 위험 신호를 보낸다.

```text
POST /v1/guardian-alerts
X-OnMom-Schema: guardian-alert-v1
Authorization: Bearer <Jetson token>
```

```json
{
  "schema_version": 1,
  "alert_id": "fall-20260729-001",
  "occurred_at": 1785319200000,
  "severity": "HIGH",
  "category": "SAFETY",
  "title": "낙상 의심",
  "message": "거실 상태를 확인해 주세요.",
  "source": "camera.fall",
  "contains_raw_data": false
}
```

허용 단계는 `INFO`, `CAUTION`, `HIGH`, `CRITICAL`이다. 허용 분류는
`SAFETY`, `HEALTH`, `ACCESS`, `ENVIRONMENT`, `SYSTEM`이다. 사진·음성·대화,
RFID UID, 전화번호, 파일 경로 표식은 거부한다.

보호자 앱은 아래 API를 사용한다.

```text
GET  /v1/guardian-alerts?after=<sequence>&limit=50
POST /v1/guardian-alerts/ack
```

## Home Assistant 위험 알림

`home_assistant/package_onmom.yaml.example`에는 승인되지 않은 RFID 출입을
식별정보 없는 보호자 알림으로 바꾸는 예제가 포함되어 있다.

Home Assistant `secrets.yaml`에 추가한다.

```yaml
onmom_jetson_alert_url: "http://JETSON_IP:8765/v1/guardian-alerts"
```

## IoT 제어 설정

앱은 Jetson의 허용 목록에 등록된 장치와 동작만 볼 수 있다. Home Assistant
토큰과 실제 `entity_id`는 Jetson 밖으로 나가지 않는다.

1. Home Assistant 프로필에서 장기 액세스 토큰을 만든다.
2. Jetson에서 토큰 파일을 저장한다.

```bash
mkdir -p ~/.config/onmom
nano ~/.config/onmom/home_assistant_token
chmod 600 ~/.config/onmom/home_assistant_token
```

3. 장치 설정을 만든다.

```bash
cd ~/Jetson
cp iot_devices.json.example iot_devices.json
nano iot_devices.json
```

Home Assistant 개발자 도구의 `상태` 화면에서 실제 `entity_id`를 확인해
예제 값을 바꾼다. 허용 도메인은 `light`, `switch`, `fan`, `lock`, `cover`다.

4. 서비스를 재시작한다.

```bash
systemctl --user restart onmom-derived-insights.service
curl http://localhost:8765/health
```

`iot.configured`가 `true`면 앱의 `IoT` 탭에서 장치 상태와 허용 동작을 볼 수
있다.

## API 확인

```bash
TOKEN=$(cat ~/.config/onmom/jetson_sync_token)

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8765/v1/iot/devices

curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-OnMom-Schema: iot-command-v1" \
  -H "Content-Type: application/json" \
  -d '{"device_id":"living_room_light","action":"TURN_ON"}' \
  http://localhost:8765/v1/iot/commands
```

임의 `entity_id`는 앱 요청에 넣을 수 없다. `iot_devices.json`의 장치 ID와
동작만 서버가 Home Assistant 서비스 호출로 변환한다.
