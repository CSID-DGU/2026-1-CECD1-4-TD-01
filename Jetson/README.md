# Jetson 적응형 컨텍스트 서비스

이 서버는 Android, Home Assistant/RFID, 자세, rPPG 분석기가 만든 파생 결과만 누적하고 상담용 맥락과 제한된 응답 전략 가중치를 만듭니다. 사진·음성 파일, URI, 통화번호, RFID UID, 앱 이벤트 원문, 대화 원문은 스키마 단계에서 거부합니다.

전체 설치·연동·운영 방법은 [ADAPTIVE_SERVICE.md](ADAPTIVE_SERVICE.md)를 먼저 확인하세요.
개발자 구조화 수치 전송·조회 방법은 [ANALYSIS_SNAPSHOT.md](ANALYSIS_SNAPSHOT.md)에 있습니다.
카메라 분석 모듈 연결 규격은 [CAMERA_BRIDGE.md](CAMERA_BRIDGE.md)에 있습니다.
보호자 위험 알림과 IoT 제어 설정은 [GUARDIAN_IOT.md](GUARDIAN_IOT.md)에 있습니다.

## 1. Jetson 영구 설치

외부 Python 패키지는 필요하지 않습니다. 다음 스크립트를 실행하면 토큰 생성 또는 유지, 권한 설정, Python 문법 검사, 사용자 서비스 등록과 즉시 실행이 한 번에 끝납니다.

```bash
cd ~/Jetson
sh install_user_service.sh
```

토큰은 `~/.config/onmom/jetson_sync_token`에 저장되고 소유자만 읽도록 권한 `0600`을 적용합니다. 로그인 전부터 시작해야 하는 headless Jetson이라면 다음 명령도 한 번 실행하세요.

```bash
sudo loginctl enable-linger "$USER"
```

상태 확인:

```bash
systemctl --user status onmom-derived-insights.service --no-pager -l
curl http://localhost:8765/health
```

정상 응답에는 다음 값이 포함됩니다.

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

토큰을 바꾸려면 토큰 파일을 새 값으로 교체하고 권한 `0600`을 적용한 뒤 서비스를 재시작하세요.

## 2. Android 앱 연결

앱의 `설정 → Jetson 파생 정보 전송`에 다음을 입력합니다.

```text
주소: http://JETSON_사설_IP:8765/v1/derived-insights
인증 토큰: 설치 스크립트가 표시한 토큰
```

`iot.local`이 Android에서 해석되면 `http://iot.local:8765/v1/derived-insights`를 사용할 수 있습니다. 해석되지 않으면 Jetson에서 `hostname -I`로 확인한 사설 IPv4 주소를 입력하세요. 예: `http://10.61.230.134:8765/v1/derived-insights`.

개발자 APK만 사설 IPv4의 평문 HTTP를 허용합니다. release APK나 공인 주소는 HTTPS가 필요합니다.

`상담용 파생 요약 전송`을 한 번 누르면 주소는 앱 설정에, 토큰은 Android Keystore 암호화 키로 보호되어 저장됩니다. 앱이나 Jetson을 재시작해도 다시 입력할 필요가 없습니다. 앱 데이터 삭제·재설치 또는 토큰 교체 시에만 다시 입력합니다.

분석 단계의 숫자·범주형 결과가 필요하면 같은 창의 `개발자 분석 수치만 수동 전송`을 누릅니다. 이 동작은 자동 실행되지 않으며 상담 프롬프트·정책 학습과 분리된 테이블에 저장됩니다.

토큰을 저장한 뒤에는 상담 중 다음 파생 정보가 자동 누적됩니다.

- Health Connect, 생활 패턴, Gallery, 최근 음성 감정 요약
- 현재 대화의 주제 코드, 길이 구간, 음성 여부, 사용한 응답 전략
- 신뢰도 높은 음성 정서의 상담 전후 변화

대화 원문은 전송하지 않습니다. 앱 재시작 사이의 전후 비교도 원문 없이 파생 수치와 전략만 앱 내부에 보존합니다.

## 3. 보호자 알림과 IoT

- 위험 신호 입력: `POST /v1/guardian-alerts`
- 보호자 앱 조회: `GET /v1/guardian-alerts`
- 장치 조회: `GET /v1/iot/devices`
- 허용 동작 실행: `POST /v1/iot/commands`

IoT 제어 전 `iot_devices.json`과 Home Assistant 장기 액세스 토큰을 설정하세요.

## 4. 저장 위치

```text
~/Jetson/data/latest_derived_insights.json
~/Jetson/data/onmom_context.db
~/.config/onmom/jetson_sync_token
```

## 5. HTTPS 직접 실행

```bash
python3 derived_insight_server.py \
  --host 0.0.0.0 \
  --port 8765 \
  --certfile /path/to/fullchain.pem \
  --keyfile /path/to/privkey.pem
```

앱 주소는 `https://호스트:8765/v1/derived-insights`로 입력합니다.

## 6. 테스트

저장소 루트에서:

```bash
python3 -m unittest discover -s Jetson -v
python3 -m py_compile Jetson/*.py
bash -n Jetson/install_user_service.sh
```
