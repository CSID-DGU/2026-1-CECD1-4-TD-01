# ESP32-P4 MIPI-CSI 카메라 송신기

Espressif `esp_video` 2.3.0의 `m2m` 예제 위에 WT9932P4-TINY용 기능을 추가한 펌웨어다.

## 현재 확정 사양

- 카메라: OV5647, MIPI-CSI, SCCB SDA=GPIO7/SCL=GPIO8
- 영상: 하드웨어 JPEG 1280x960, 품질 50, 촬영 간격 1700ms
- Wi-Fi 브리지: DT-06 TCP Client
- 영상 UART: UART1, TX=GPIO32, RX=GPIO33, 1000000bps, 8N1
- OLED: SSD1306 128x32, SDA=GPIO28, SCL=GPIO29, 주소 0x3C/0x3D 자동 검색
- 스피커: GPIO30 LEDC PWM
- DT-06 상태: GPIO31 입력
- GPIO34: 예비
- GPIO36~38: 사용하지 않음

전체 배선과 DT-06 설정은 [WIRING_DT06_OLED_SPEAKER_KO.md](WIRING_DT06_OLED_SPEAKER_KO.md)를 참고한다.

## Windows 빌드

ESP-IDF v5.5.4와 EIM이 설치된 PowerShell에서 실행한다.

```powershell
cd "C:\Users\baak_jun\Documents\iot cam N16R32 WT013P4"
.\firmware\p4-camera-sender\prepare-build-flash.ps1 -BuildOnly
```

FUSB가 COM10으로 잡힌 경우 플래시한다.

```powershell
.\firmware\p4-camera-sender\prepare-build-flash.ps1 -Port COM10 -NoMonitor
```

현재 빌드 결과는 `C:\tmp\p4sender\build\m2m.bin`에 생성된다.

## 정상 부팅 로그

다음 항목이 보여야 한다.

```text
OLED ready: SSD1306 128x32 address=0x3c SDA=28 SCL=29
speaker PWM ready: GPIO30
SCCB/I2C ACK at 7-bit address 0x36
Detected Camera sensor PID=0x5647
video UART ready: port=1 tx=32 rx=33 baud=1000000
JPEG snapshots 1280x960 quality=50 interval=1700ms UART=1000000
shot=... jpeg=...B capture=...ms encode=...ms uart=...ms total=...ms result=ESP_OK
```

OLED가 없으면 `OLED not available` 경고만 출력하고 카메라 송신은 계속한다.