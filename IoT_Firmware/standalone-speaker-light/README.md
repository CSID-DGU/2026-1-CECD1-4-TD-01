# 독립형 ESP-12N PWM 스피커 + 조명

카메라 프로젝트와 무관한 별도 ESP8266 기기다. ESPHome 네이티브 API로
Home Assistant에서 LED와 PWM 입력 스피커 모듈을 제어한다.

## 현재 배선

| ESP-12N | 연결 | 설명 |
|---|---|---|
| D4 / GPIO2 | LED 제어 | PWM 밝기 조절, ESP8266 부트 핀 |
| D1 / GPIO5 | 스피커 모듈 `IN` | 50% 듀티 가청 PWM |
| 3.3V | 스피커 모듈 `VCC` | 현재 확인된 모듈 전원 |
| GND | 스피커 모듈 `GND` 및 LED GND | 공통 접지 |

DC HIGH에서는 작은 잡음만 났지만 D1(GPIO5)의 500/1000/2000Hz PWM 시험은
모두 정상 청음됐다. 따라서 가변저항이 달린 PWM/아날로그 입력 스피커 모듈로
판정하며 펌웨어 1.2.0부터 가청 주파수 PWM으로 제어한다.

GPIO2(D4)는 ESP8266 부트 시 HIGH여야 하므로 LED 회로가 이 핀을 강제로 LOW로
끌어내리지 않아야 정상 부팅된다. 단일 LED는
`D4(GPIO2) -> 220~330Ω 저항 -> LED 애노드`, `LED 캐소드 -> GND`로 연결한다.

## Home Assistant 기능

- `Light`: 켜기, 끄기, 밝기, 펄스, 점멸
- `Continuous Tone`: 현재 주파수로 연속음 켜기/끄기
- `Beep Duration`: 시험 비프 길이 50~2000ms
- `Tone Frequency`: 비프 주파수 100~5000Hz
- `Test Buzzer`: 설정한 주파수와 길이로 한 번 울림
- `Doorbell`: 1200Hz와 800Hz로 두 번 울림
- `Alert`: 1500Hz로 세 번 울림
- `Stop Speaker`: 실행 중인 비프를 즉시 정지
- 사용자 동작 `beep(duration_ms)`: 현재 음높이로 원하는 길이만큼 울림
- 사용자 동작 `stop_audio`: 모든 스피커 출력 정지

장치 주소는 `esp12n-speaker-light.local` 또는 `10.61.230.115`이며 ESPHome
네이티브 API 포트는 6053이다. Home Assistant의 ESPHome 통합에는
`ESP12N Speaker Light`로 등록돼 있다.

`home-assistant-card.yaml`은 대시보드용 Entities 카드 예시다.

## Home Assistant 자동화 예시

```yaml
action: button.press
target:
  entity_id: button.esp12n_speaker_light_alert
```

문 열림, 센서 경고, 타이머 완료 같은 자동화의 동작으로 위 버튼을 호출하면
된다. 상황별로 `doorbell`, `alert`, `test_buzzer`를 구분해 사용할 수 있다.

## 오디오 한계

현재 ESP8266의 한 핀 PWM 구성은 비프, 음높이 변화, 짧은 알림 패턴에 적합하다.
Home Assistant가 보내는 MP3/WAV 파일, 음악 스트림, TTS나 음성 안내를
`media_player`처럼 재생할 수는 없다. 그런 기능이 필요하면 ESP32-S3, I2S
앰프(예: MAX98357A), 실제 스피커 조합이 필요하다.

## 다시 빌드/업로드

```powershell
cd "C:\Users\baak_jun\Documents\iot cam N16R32 WT013P4\standalone-speaker-light"
& "$env:LOCALAPPDATA\codex-esphome-venv\Scripts\python.exe" -m esphome config .\speaker-light.yaml
& "$env:LOCALAPPDATA\codex-esphome-venv\Scripts\python.exe" -m esphome run .\speaker-light.yaml --device 10.61.230.115
```

최초 업로드 전 원래 4MB 플래시는 `firmware-backups` 폴더에 백업했다.