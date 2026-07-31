# ESP8266 Wi-Fi 설정 포털 사용법

이 펌웨어는 ESP8266을 다시 컴파일하지 않고도 휴대전화나 PC에서 다음 값을 바꿀 수 있게 합니다.

- 2.4GHz Wi-Fi 또는 휴대전화 핫스팟 SSID
- Wi-Fi 비밀번호
- Jetson Orin Nano의 IPv4 주소
- Jetson 수신 TCP 포트(기본값 9000)

설정은 ESP8266 EEPROM에 저장되며 전원을 껐다 켜도 유지됩니다.

## 현재 보드에 올라간 최종 상태

- 보드: ESP8266EX / ESP-12N, COM5
- UART 입력: 2,000,000 baud, 8-N-1
- 설정 AP: `IoTCam-Setup-B3A6`
- 설정 AP 비밀번호: `iotcam-setup`
- 설정 페이지: `http://192.168.4.1`
- 부팅할 때마다 설정 AP를 5분 동안 엽니다.
- 저장된 Wi-Fi 연결에 실패하면 설정 AP를 계속 유지합니다.
- 저장된 Wi-Fi가 연결되면 5분 뒤 설정 AP를 닫습니다.

제품 외부에 공개하기 전에는 `src/main.cpp`의 `kSetupPassword`를 반드시 개인 비밀번호로 바꾸세요.

## 휴대전화 핫스팟으로 바꾸는 정확한 순서

ESP8266은 2.4GHz만 지원합니다. 핫스팟 설정에서 2.4GHz 또는 호환성 모드를 켜세요.

한 대의 휴대전화만 쓸 때는 그 휴대전화가 자기 핫스팟에 Wi-Fi로 접속할 수 없으므로 순서가 중요합니다.

1. 휴대전화 핫스팟의 이름과 비밀번호를 먼저 메모합니다.
2. 휴대전화 핫스팟을 잠시 끕니다.
3. ESP8266 전원을 껐다 켭니다.
4. 휴대전화 Wi-Fi 목록에서 `IoTCam-Setup-B3A6`에 연결합니다.
5. 비밀번호 `iotcam-setup`을 입력합니다.
6. “인터넷이 없음” 경고가 떠도 이 네트워크 연결을 유지합니다.
7. 브라우저 주소창에 `http://192.168.4.1`을 입력합니다.
8. 핫스팟 SSID, 비밀번호, Jetson IPv4, TCP 포트 9000을 입력합니다.
9. `저장 후 재연결`을 누릅니다.
10. ESP 설정망 연결이 끊기면 휴대전화 핫스팟을 다시 켭니다.
11. Jetson도 같은 휴대전화 핫스팟에 연결합니다.

두 번째 휴대전화나 PC가 있으면 핫스팟은 계속 켜 두고, 다른 장치로 ESP 설정망에 접속하면 더 간단합니다.

## 설정 AP가 사라진 뒤 다시 접속

설정 AP가 닫혀도 웹 서버와 영상 전송은 계속 실행됩니다. 접속 주소만 바뀝니다.

- 설정 AP에 연결했을 때: `http://192.168.4.1`
- 핫스팟에 연결된 뒤: 설정 화면에 표시되는 `http://10.61.230.x/`
- 이름 해석을 지원하는 장치: `http://iotcam-bridge.local/`

현재처럼 휴대전화가 핫스팟을 열고 Windows PC가 있는 경우에는 다음 순서가 가장 확실합니다.

1. 핫스팟을 켠 채 ESP의 RST 버튼을 누릅니다.
2. Windows를 `IoTCam-Setup-B3A6`에 잠시 연결하고 `http://192.168.4.1`을 엽니다.
3. `같은 Wi-Fi 접속 주소`에 표시된 주소를 복사합니다.
4. Windows를 휴대전화 핫스팟에 다시 연결합니다.
5. 복사한 주소를 브라우저에서 엽니다.

`iotcam-bridge.local`은 Windows 또는 Android 환경에 따라 이름 해석이 안 될 수 있으므로 숫자 IP 주소가 가장 확실합니다. 휴대전화의 핫스팟 연결 기기 목록에서는 MAC 주소 `5C:CF:7F:B8:B3:A6`으로도 ESP를 찾을 수 있습니다.

## Jetson 주소 확인

Jetson이 핫스팟에 연결된 뒤 Jetson 터미널에서 실행합니다.

```bash
hostname -I
```

출력 중 핫스팟 대역의 IPv4 주소를 사용합니다. 예를 들어 `192.168.45.120`입니다. `127.0.0.1`, Docker 컨테이너 IP, Home Assistant VM IP를 넣으면 안 됩니다.

Jetson의 IP가 핫스팟을 켤 때마다 바뀌면 다음 중 하나를 사용합니다.

- 설정 포털에서 새 Jetson IP를 다시 저장
- 휴대전화 핫스팟의 고정 IP 또는 DHCP 예약 기능 사용
- 이후 Jetson을 자체 핫스팟으로 구성해 주소를 고정

## 화면에서 확인할 상태

- `Wi-Fi 연결됨`: ESP가 핫스팟에 연결됨
- `Jetson TCP 연결됨`: ESP가 Jetson의 9000 포트에 연결됨
- `UART RX`: P4에서 ESP로 들어온 누적 바이트
- `전송됨`: Jetson으로 보낸 누적 바이트
- `버림`: Wi-Fi 또는 Jetson 연결이 없어서 버린 바이트

`UART RX`가 계속 0이면 P4 송신 펌웨어 또는 TX 배선을 확인합니다. `UART RX`는 증가하지만 `Jetson TCP`가 끊김이면 Jetson 주소, 9000 포트 리스너, 방화벽 또는 핫스팟의 장치 격리를 확인합니다.

## 핫스팟 장치 격리 주의

ESP와 Jetson은 같은 핫스팟에 연결되는 것만으로 충분하지 않을 수 있습니다. 일부 휴대전화는 연결된 기기끼리 직접 통신하지 못하게 격리합니다.

Jetson에서 9000 포트 리스너를 켠 뒤 설정 화면의 `Jetson TCP`가 연결됨으로 바뀌는지 확인하세요. 계속 끊김이면 다음 대안을 사용합니다.

- Jetson Orin Nano가 직접 Wi-Fi 핫스팟을 열게 구성
- 휴대용 2.4GHz 공유기 사용
- 장치 간 통신을 허용하는 Android 핫스팟 사용

## 배선

| WT9932P4-TINY | ESP-12N | 용도 |
|---|---|---|
| GPIO23 (TX) | GPIO3 / RXD0 | 2Mbps UART 영상 데이터 |
| GND | GND | 공통 기준 전압 |

ESP TXD0에서 P4로 돌아가는 선은 현재 프로토콜에 필요하지 않습니다. ESP와 P4에 각각 전원을 공급하더라도 GND는 반드시 연결해야 합니다.

USB-UART 어댑터의 TX와 P4 GPIO23 TX를 ESP GPIO3에 동시에 연결해 두면 두 출력이 충돌할 수 있습니다. 업로드가 끝난 뒤에는 USB-UART의 TX 선을 빼거나, 어댑터를 분리한 상태에서 P4 TX를 연결하세요.

## VS Code에서 수정할 파일

- 설정 화면 HTML/CSS/JS: `src/web_ui.h`
- Wi-Fi, AP, API, UART→TCP 로직: `src/main.cpp`
- EEPROM 저장 형식: `src/config_store.h`, `src/config_store.cpp`
- PC 미리보기 서버: `tools/ui_preview_server.py`

미리보기 실행:

```powershell
cd "C:\Users\baak_jun\Documents\iot cam N16R32 WT013P4\firmware\esp12n-bridge"
python .\tools\ui_preview_server.py
```

브라우저에서 `http://127.0.0.1:8765`를 엽니다. 정확한 390px 모바일 미리보기는 `http://127.0.0.1:8765/preview/mobile`입니다.

빌드만 수행:

```powershell
.\prepare-upload.ps1 -BuildOnly
```

COM5에 업로드:

```powershell
.\prepare-upload.ps1 -Port COM5
```

설정 화면의 `설정 초기화`를 누르면 EEPROM의 Wi-Fi와 Jetson 설정을 지우고 재부팅합니다. 그러면 설정 AP가 계속 열린 상태가 됩니다.
