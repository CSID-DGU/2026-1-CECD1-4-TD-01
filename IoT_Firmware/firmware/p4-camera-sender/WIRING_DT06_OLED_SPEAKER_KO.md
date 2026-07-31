# WT9932P4-TINY + DT-06 + SSD1306 OLED + 스피커 배선

## 확정 GPIO 배치

GPIO26~38 범위만 사용하며, GPIO36~38은 스트래핑/업로드/디버그 용도라 비워 둔다.

| Tiny 핀 | 방향 | 연결 대상 | 용도 |
|---|---:|---|---|
| GPIO26~27 | - | 미사용 | 핀헤더 진단 후 사용하지 않음 |
| GPIO28 | 양방향 | OLED SDA | SSD1306 I2C 데이터 |
| GPIO29 | 출력 | OLED SCL | SSD1306 I2C 클럭 |
| GPIO30 | 출력 | 스피커 IN | LEDC PWM 테스트음 |
| GPIO31 | 입력 | DT-06 STATE | Wi-Fi STA 연결 상태, 선택 사항 |
| GPIO32 | 출력 | DT-06 RXD | JPEG 영상 UART 송신 |
| GPIO33 | 입력 | DT-06 TXD | 선택적 제어 UART; 영상 시험에서는 분리 |
| GPIO34 | - | 미사용 | 확장용 예비 |
| GPIO36~38 | - | 연결 금지 | 스트래핑/UART0/업로드 보호 |

## 전원

| Tiny 전원 | 연결 대상 |
|---|---|
| 5V | DT-06 VCC, 스피커 VCC |
| 3.3V | OLED VCC |
| GND | DT-06/OLED/스피커의 모든 GND |

DT-06 보드의 `LEVEL:3.3V`는 UART 로직 레벨 표시다. VCC는 5V에 연결한다.
스피커의 파란 가변저항은 처음에는 최소로 두고 조금씩 올린다.

## DT-06 웹 설정

1. 최초 전원에서는 UART 선을 빼고 VCC/GND만 연결한다.
2. `IoTCam-Setup-xxxx` AP에 접속한다.
3. `http://192.168.4.1`을 연다.
4. 다음 값을 저장하고 재시작한다.

```text
설정 AP 암호: iotcam-setup
STA SSID/암호: 사용할 2.4GHz Wi-Fi 또는 핫스팟
Jetson host: 10.61.230.134
Jetson port: 9000
UART: 1000000, 8-N-1 (펌웨어 고정)
```

5. 전원을 끄고 GPIO32->RXD를 연결한다. 제어 채널이 필요할 때만 GPIO33<-TXD를 연결한다.

## OLED 화면

OLED 주소는 부팅 때 0x3C와 0x3D를 자동 검사한다. 연결되지 않아도 카메라 송신은 계속된다.
정상 스트리밍 중에는 다음 정보를 1초마다 갱신한다.

```text
IOT CAMERA STREAM
CAM 1280X960
SNAP:1/S DROP:0
WIFI: LINK
```

## 스피커 음 패턴

- 부팅 후 짧은 880Hz 1회: 주변장치 초기화 완료
- 카메라 스트리밍 시작 시 1200Hz, 1600Hz 2회: 카메라/인코더 시작 성공
- 카메라 SCCB 오류 시 220Hz 길게 1회 후 안전 정지

스피커가 연결되지 않아도 GPIO30 PWM만 출력되며 다른 기능에는 영향이 없다.