# DT-06 커스텀 펌웨어 적용 순서

목표는 카메라 해상도와 JPEG 품질을 낮추지 않고 DT-06 UART 병목을
`460800` baud에서 `1000000` baud로 올리는 것이다.

## 안전 배선

플래시할 때는 DT-06을 Tiny 보드와 완전히 분리한다. 전원도 한 곳에서만
공급한다.

| USB-UART | DT-06 |
|---|---|
| 5V | VCC |
| GND | GND |
| TX (3.3V logic) | RXD |
| RX (3.3V logic) | TXD |

`5V`는 전원 핀이고 UART TX/RX 신호는 반드시 `3.3V logic`이어야 한다.
전용 USB-UART가 없으면 ESP8266 개발 보드의 USB-UART를 사용할 수 있지만,
개발 보드의 ESP 칩을 RESET 상태로 고정해야 한다. 개발 보드의 정확한
모델과 핀 표기를 확인하기 전에는 연결하지 않는다.

## 부트로더 진입

1. SW1(FLASH/GPIO0)을 누른다.
2. SW1을 누른 채 SW2(RESET)를 눌렀다 놓는다.
3. SW1을 놓는다.

각 명령 전 위 순서를 다시 수행한다.

## 명령

먼저 통신과 플래시 크기만 확인한다. 이 명령은 내용을 변경하지 않는다.

```powershell
.\dt06-tool.ps1 -Port COM11 -Action Probe
```

그다음 전체 순정 펌웨어를 백업한다.

```powershell
.\dt06-tool.ps1 -Port COM11 -Action Backup
```

출력된 백업 파일의 전체 경로를 사용해 새 펌웨어를 쓴다. 백업 파일이
없거나 크기가 다르면 스크립트가 쓰기를 거부한다.

```powershell
.\dt06-tool.ps1 -Port COM11 -Action Flash `
  -BackupFile ".\backups\dt06-stock-YYYYMMDD-HHMMSS-2097152.bin"
```

문제가 생기면 같은 방식으로 순정 펌웨어를 복원할 수 있다.

```powershell
.\dt06-tool.ps1 -Port COM11 -Action Restore `
  -BackupFile ".\backups\dt06-stock-YYYYMMDD-HHMMSS-2097152.bin"
```

## 정상 부팅 후

1. SW2를 한 번 눌러 정상 부팅한다.
2. 휴대폰/PC에서 `IoTCam-Setup-xxxx`에 연결한다.
3. 암호 `iotcam-setup`을 입력한다.
4. `http://192.168.4.1`에서 핫스팟 SSID/암호와 Jetson
   `10.61.230.134`, 포트 `9000`을 저장한다.
5. Tiny 연결은 `GPIO32(TX) -> DT-06 RXD`, `GND -> GND`,
   `5V -> VCC`만 사용한다. DT-06 TXD는 당분간 분리한다.

Tiny와 DT-06 모두 UART `1000000`, 8-N-1로 맞춘 뒤 고품질
1280x960 JPEG 성공률을 다시 측정한다.