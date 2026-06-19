# Counseling Desktop

Android 앱의 핵심 사용 흐름을 Windows에서 바로 띄워 확인하기 위한 데스크톱 테스트 앱입니다. 이제 `exe` 빌드는 사용하지 않고, Python으로 실행합니다.

## 실행

```powershell
python .\DesktopApp\app.py
```

처음 LLM을 실행할 때는 `gemma-4-E4B-it.litertlm` 로드와 GPU/CPU 캐시 준비 때문에 1분 이상 걸릴 수 있습니다. 창이 멈춘 것처럼 보여도 첫 응답 생성 중일 수 있습니다.

## 주요 기능

- 상담 채팅 UI
- `LLMmodels/models/gemma-4-E4B-it.litertlm` 모델 로드 및 응답 생성
- 갤러리 이미지/폴더 선택 후 로컬 요약
- WAV 음성 파일 감정 분석
- 중요 기억 저장 및 삭제
- 상담 세션 저장, 목록, 내보내기, 불러오기
- 프롬프트 미리보기
- 모델 경로, backend, 외부 명령 설정
- Windows에서 제외된 Android 전용 기능 안내

## 모델 경로

기본 LLM 경로:

```text
LLMmodels/models/gemma-4-E4B-it.litertlm
```

앱은 기본적으로 위 파일을 찾습니다. 다른 위치를 쓰려면 설정 탭에서 모델을 선택하거나 환경변수로 지정할 수 있습니다.

```powershell
$env:COUNSELING_LLM_MODEL = "C:\path\to\gemma-4-E4B-it.litertlm"
python .\DesktopApp\app.py
```

backend는 `cpu`, `gpu`, `npu` 중 하나를 설정할 수 있습니다.

```powershell
$env:COUNSELING_LLM_BACKEND = "gpu"
python .\DesktopApp\app.py
```

GPU에서 문제가 나면 `cpu`로 바꿔서 확인하세요.

상담 세션, 중요 기억, LiteRT-LM 캐시는 기본적으로 저장소 루트의 `.counseling_desktop` 폴더에 저장됩니다. 다른 저장 위치가 필요하면 다음 환경변수를 사용합니다.

```powershell
$env:COUNSELING_DESKTOP_DATA_DIR = "C:\path\to\data"
python .\DesktopApp\app.py
```

## PC와 휴대폰 차이

휴대폰 앱은 Android용 LiteRT-LM 런타임을 사용하고, 이 데스크톱 앱은 Windows Python 패키지인 `litert-lm-api`의 `litert_lm.Engine`을 사용합니다. 같은 `.litertlm` 모델이라도 런타임과 backend가 다르기 때문에 PC에서는 첫 로드가 느리거나 GPU 드라이버 영향이 있을 수 있습니다.

현재 코드 기준으로 PC에서도 `LLMmodels/models/gemma-4-E4B-it.litertlm` 파일을 `litert_lm.Engine`으로 직접 로드하도록 되어 있습니다.

## 설치 패키지

```powershell
python -m pip install -r .\DesktopApp\requirements.txt
```

필수 패키지:

- `litert-lm-api`
- `numpy`
- `onnxruntime`
- `Pillow`

## 자가 테스트

기본 리소스, 모델 파일, 런타임 패키지를 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test
```

LLM 모델을 실제로 로드하고 샘플 응답을 생성합니다. 첫 실행은 오래 걸릴 수 있습니다.

```powershell
python .\DesktopApp\app.py --self-test-llm
```

음성 ONNX 모델 추론을 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test-voice
```

갤러리 이미지 수집과 요약 로직을 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test-gallery
```

상담 세션 저장/불러오기 round-trip을 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test-session
```

프롬프트 구성이 현재 상담 맥락을 포함하는지 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test-prompt
```

외부 LLM 명령 어댑터만 따로 확인합니다.

```powershell
python .\DesktopApp\app.py --self-test-external-llm
```

## 사용 흐름

1. `python .\DesktopApp\app.py`로 앱을 실행합니다.
2. 상담 탭에서 메시지를 입력합니다.
3. `litert-lm-api`가 설치되어 있으면 앱 실행 직후 백그라운드에서 LLM 엔진을 미리 로드합니다. 상태 문구가 `LiteRT-LM 엔진 로드 완료` 또는 `LiteRT-LM 엔진 이미 로드됨`으로 바뀌면 이후 응답에서는 같은 엔진을 재사용합니다.
4. 갤러리 탭에서 이미지 파일이나 폴더를 선택하면 이미지 요약이 상담 맥락에 반영됩니다.
5. 음성 탭에서 WAV 파일을 선택하면 감정 분석 결과가 상담 맥락에 반영됩니다.
6. 중요 기억 탭에서 계속 참고할 내용을 저장합니다.
7. 프롬프트 탭에서 실제 LLM에 들어가는 맥락 구성을 확인합니다.
8. 설정 탭에서 모델 경로와 backend를 확인하거나 변경합니다.

## 제외한 기능

- Android 통화 기록 기반 피노타입
- Android 앱 사용량 기반 피노타입
- Health Connect 직접 연동
- Windows `exe` 패키징

위 기능들은 현재 데스크톱 확인 목적과 맞지 않거나 Android 시스템 권한에 의존하므로 제외했습니다.

## 문제 확인 포인트

- 모델 파일이 `LLMmodels/models/gemma-4-E4B-it.litertlm`에 있는지 확인합니다.
- `python -m pip show litert-lm-api`로 LiteRT-LM Python 패키지가 설치되어 있는지 확인합니다.
- GPU backend에서 응답이 안 나오면 `COUNSELING_LLM_BACKEND=cpu`로 다시 실행합니다.
- 첫 실행은 캐시 생성 때문에 오래 걸릴 수 있습니다. Python API 경로는 엔진을 한 번 로드한 뒤 재사용하지만, `litert-lm` CLI 또는 외부 명령 경로는 요청마다 새 프로세스를 실행하므로 모델을 매번 다시 로드할 수 있습니다.
- PowerShell 출력 인코딩 문제는 앱에서 UTF-8로 보정합니다.

## 주의

이 앱의 상담 응답은 의료 진단이나 치료를 대체하지 않습니다.
