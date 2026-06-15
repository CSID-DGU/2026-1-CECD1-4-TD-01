# 2026-06-01 Gallery Analysis merge record

## 목적

`Analysis/Image/GalleryAnalysisModule`에 새로 추가된 이미지 분석 모듈을 `Frontend` 상담 앱에 합치기 위한 사전 분석 기록이다. 오늘 기준일은 2026-06-01이다.

이번 문서는 실제 병합 코드를 적용한 기록이 아니라, 병합 전에 확인한 기능 범위, 연결 지점, 필요한 Gradle/권한 변경, 위험 요소, 권장 작업 순서를 정리한 것이다.

## 현재 구조

- 분석 모듈 위치: `Analysis/Image/GalleryAnalysisModule`
- 프론트 앱 위치: `Frontend`
- 분석 모듈 형태: 독립 Android application 프로젝트
- 프론트 형태: 단일 `:app` Android application 프로젝트, Compose UI
- 분석 모듈 Kotlin 파일 수: 27개
- 프론트에는 이미 갤러리 권한/목록/썸네일 화면이 있음:
  - `Frontend/app/src/main/java/com/example/counseling/GalleryData.kt`
  - `Frontend/app/src/main/java/com/example/counseling/GalleryScreen.kt`

## 분석 모듈 기능 요약

분석 모듈은 갤러리 이미지 원본을 저장하지 않고 MediaStore 메타데이터와 썸네일 기반 ML Kit 분석 결과를 사용해 상담용 보조 컨텍스트를 만든다.

주요 흐름:

1. `GalleryPermissionHelper`가 이미지 접근 권한을 확인한다.
2. `GalleryScanner`가 최근 N개월 MediaStore 이미지 메타데이터를 스캔한다.
3. `ImageFilter`가 스크린샷, 다운로드, 메신저, 편집/저품질 이미지 등을 제외한다.
4. `ThumbnailLoader`가 분석용 축소 이미지를 로드한다.
5. `MlKitImageAnalyzer`가 ML Kit Image Labeling, Object Detection, Face Detection을 실행한다.
6. `ActivityClassifier`가 이미지별 활동 카테고리를 분류한다.
7. `SisyphusReviewPipeline`이 낮은 확신/충돌 결과를 후처리한다.
8. `DuplicateGrouper`가 비슷한 시간대/이미지를 activity event로 묶는다.
9. `PreferenceAnalyzer`, `PeriodChangeAnalyzer`, `TrendAnalyzer`가 반복 활동, 최근 14일/기준 30일 변화, 시간 분포를 만든다.
10. `VisualRiskCueDetector`, `SafetyDecisionEngine`, `WellbeingDomainAnalyzer`가 상담 안전 보조 신호와 WHO-aligned wellbeing domain prompt를 만든다.
11. 최종 결과는 `GalleryAnalysisResult`이며, 상담에 바로 넣을 핵심 문자열은 `result.counselingReport.llmContextSummary`이다.

## 공개 진입점

- `GalleryAnalysisEngine(context, config).analyze(progress): GalleryAnalysisResult`
  - 갤러리 권한이 없으면 `GalleryAnalysisResult.empty()`를 반환한다.
  - progress callback은 `(percent: Int, message: String)` 형태다.
  - 기본 설정은 최근 6개월, 최근 14일, 기준 30일, 썸네일 long side 384, audit export enabled이다.

- `GalleryAnalysisEngine.assessUserTextSafety(userText, lastReport): SafetyAssessment`
  - 이미지 분석 결과와 사용자 텍스트를 결합해 안전 응답 정책을 다시 판단할 수 있는 보조 함수다.

- `GalleryAnalysisEngine.toPrettyJson(result): String`
  - 디버그/검증용 JSON 출력 함수다.

## 결과 모델에서 프론트가 우선 사용할 값

- `GalleryAnalysisResult.summary`
  - 전체 이미지 수, 분석 대상 수, 실제 분석 수, 이벤트 수, 소요 시간

- `GalleryAnalysisResult.qualityReport`
  - ML Kit 성공률, 분류 커버리지, unknown 비율, 평균 confidence, warning

- `GalleryAnalysisResult.counselingReport.llmContextSummary`
  - LLM에 넣을 상담 컨텍스트. 병합 1차 목표는 이 값을 Chat prompt에 주입하는 것이다.

- `GalleryAnalysisResult.counselingReport.safetyAssessment`
  - `NONE`, `LOW_OBSERVE`, `MEDIUM_CHECK_IN`, `HIGH_SUPPORT`, `IMMEDIATE_DANGER`
  - `mustUseRuleBasedResponse == true`이면 자유 생성보다 룰 기반 안전 응답을 우선해야 한다.

- `GalleryAnalysisResult.counselingReport.wellbeingReport`
  - primary/secondary wellbeing domains, suggested opening, do-not-start-with, micro action 후보

- `GalleryAnalysisResult.counselingReport.visualRiskCues`
  - 이미지 기반 안전 보조 단서. 단독으로 위험 확정에 쓰면 안 된다.

## 프론트 통합 지점

프론트에는 이미 Health/Phenotype context 주입 패턴이 있다.

- Health context:
  - `ChatScreen.kt`의 `includeHealthContext`
  - `readHealthSummary(context, healthContextPeriod)`
  - `ChatPromptUtils.kt`의 `withHealthContext(...)`

- Phenotype context:
  - `ChatScreen.kt`의 `includePhenotypeContext`
  - `readPhenotypePromptContext(context)`
  - `PhenotypePrompt.kt`의 `withPhenotypeContext(...)`

이미지 분석도 같은 패턴으로 붙이는 것이 가장 작다.

권장 프론트 API:

```kotlin
data class GalleryAnalysisPromptResult(
    val contextText: String?,
    val message: String,
    val summary: GalleryAnalysisSummary? = null,
)

suspend fun readGalleryAnalysisPromptContext(context: Context): GalleryAnalysisPromptResult

fun List<ChatMessage>.withGalleryAnalysisContext(galleryContext: String?): List<ChatMessage>
```

`ChatScreen.kt`에는 `includeGalleryAnalysisContext` 토글을 추가하고, 전송 직전에 Health/Phenotype처럼 컨텍스트를 읽어 `promptMessages`에 주입하면 된다.

## 병합 방식 선택

### 권장: 프론트 앱 내부 패키지로 소스 이관

분석 모듈의 `com.example.galleryanalysis` 코드를 `Frontend/app/src/main/java/com/example/counseling/galleryanalysis`로 옮기고 package를 변경한다.

장점:

- 현재 프론트가 단일 `:app` 프로젝트라 통합 변경이 작다.
- `GalleryScreen`, `ChatScreen`, 권한 유틸과 바로 연결하기 쉽다.
- 분석 모듈의 데모 `MainActivity`와 독립 application 설정을 버릴 수 있다.

주의:

- 프론트의 기존 `GalleryImage`와 분석 모듈의 `GalleryImage` 이름이 충돌하므로 패키지를 분리하거나 이름을 바꿔야 한다.
- 분석 모듈의 `MainActivity.kt`, `styles.xml`, launcher manifest는 가져오지 않는다.

### 대안: `:galleryanalysis` Android library 모듈로 변환

분석 모듈을 별도 library 모듈로 만들려면 다음이 필요하다.

- `com.android.application`을 `com.android.library`로 변경
- `applicationId` 제거
- launcher `MainActivity`와 독립 manifest 제거 또는 debug sample로 분리
- Frontend `settings.gradle.kts`에 `include(":galleryanalysis")`
- `Frontend/app/build.gradle.kts`에 `implementation(project(":galleryanalysis"))`

장점은 모듈 경계가 명확하다는 점이고, 단점은 현재 repo 구조에서는 Gradle 변경 범위가 더 크다는 점이다.

## 필요한 Gradle 변경

프론트 `gradle/libs.versions.toml`에 추가할 후보:

- `androidx.exifinterface:exifinterface:1.4.2`
- `com.google.mlkit:image-labeling:17.0.9`
- `com.google.mlkit:object-detection:17.0.2`
- `com.google.mlkit:face-detection:16.1.7`
- `com.google.code.gson:gson:2.14.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2`
- `com.android.tools:desugar_jdk_libs:2.1.5` if Java time/desugaring 문제가 생기면 추가

프론트는 현재 `compileSdk = 36`, `targetSdk = 36`, `minSdk = 28`이고 분석 모듈은 `compileSdk = 35`, `targetSdk = 35`, `minSdk = 23`이다. 프론트에 흡수할 때는 프론트 설정을 유지하는 것이 맞다.

## 권한 상태

분석 모듈 manifest와 프론트 manifest 모두 이미 다음 이미지 권한을 선언한다.

- `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32`
- `READ_MEDIA_IMAGES`
- `READ_MEDIA_VISUAL_USER_SELECTED`

따라서 manifest 권한 추가는 거의 필요 없다. 대신 런타임 UX에서 다음을 정해야 한다.

- 전체 이미지 권한이 없고 Android 14+ 부분 접근만 있는 경우 분석 결과가 제한된다는 안내
- 채팅 컨텍스트 주입 토글을 켰을 때 권한이 없으면 갤러리 화면 또는 권한 요청으로 유도
- 이미지 분석은 시간이 걸릴 수 있으므로 진행률과 취소/재시도 상태 제공

## 개인정보와 저장 정책

분석 엔진은 원본 이미지를 export하지 않는다. 다만 audit export가 켜져 있으면 app-specific external directory 아래에 CSV/JSON이 저장된다.

저장 위치:

- `context.getExternalFilesDir(null)/GalleryAnalysisAudit/run_<timestamp>`
- `per_image_audit.csv`
- `per_event_audit.csv`
- `scenario_summary.json`

주의할 점:

- 원본 이미지는 저장하지 않지만 file name, relative path, label, object, face count, derived score가 남는다.
- 운영 앱에서는 기본값 `enableAuditExport = false`를 권장한다.
- 개발/검증용 화면에서만 audit export를 켜는 편이 안전하다.

## 상담 안전 정책상 중요한 점

모듈 자체도 “이미지만으로 진단/위험 확정 금지” 정책을 갖고 있다. 프론트 병합 시 이 전제가 깨지면 안 된다.

- `llmContextSummary`는 상담 방향 보조 정보로만 사용한다.
- 사용자에게 “사진에서 보니”처럼 직접 언급하지 않는다.
- 초기 라포 단계에서는 생활 개선 제안보다 경험 확인 질문 1개를 우선한다.
- `HIGH_SUPPORT` 또는 `IMMEDIATE_DANGER`가 나오면 룰 기반 안전 응답이 우선이다.
- visual risk cue는 단독 근거가 아니라 직접 발화/질문지/사용자 응답보다 낮은 증거 수준으로 취급한다.

## 확인된 리스크

1. 독립 모듈 README와 일부 문자열이 터미널에서 깨져 보였다. 실제 파일 인코딩 또는 콘솔 인코딩 문제인지 확인이 필요하다.
2. 분석 모듈은 Android View 기반 demo `MainActivity`를 포함한다. 프론트 Compose 앱에는 데모 Activity를 그대로 가져오면 안 된다.
3. `GalleryImage` 타입 이름이 프론트와 분석 모듈에 모두 존재한다. 패키지 분리 또는 타입명 변경이 필요하다.
4. ML Kit 의존성이 추가되면 APK 크기와 첫 분석 시간, 메모리 사용량이 늘 수 있다.
5. `AnalysisConfig.maxImages = Int.MAX_VALUE` 기본값은 실제 사용자 갤러리에서 오래 걸릴 수 있다. 프론트에서는 1차 통합 시 상한을 두는 것이 낫다. 예: 300-1000장.
6. audit export 기본값이 true다. 프론트 운영 경로에서는 false로 덮어써야 한다.
7. 현재 분석 모듈의 독립 빌드는 검증 완료되지 않았다.
   - `.\gradlew.bat :app:compileDebugKotlin` 첫 시도: JVM 8 설정으로 실패
   - Android Studio JBR로 재시도: 120초 제한에서 timeout

## 권장 작업 순서

1. 분석 코드를 프론트 내부 패키지로 이관한다.
   - 대상 패키지 예: `com.example.counseling.galleryanalysis`
   - 제외: 분석 모듈 `MainActivity.kt`, 독립 manifest launcher, styles

2. 프론트 Gradle 의존성을 추가한다.
   - ML Kit 3종
   - ExifInterface
   - Gson
   - coroutines play-services
   - 필요 시 desugar

3. `GalleryAnalysisPrompt.kt`를 만든다.
   - `readGalleryAnalysisPromptContext(context)`
   - `withGalleryAnalysisContext(...)`
   - `AnalysisConfig(enableAuditExport = false, maxImages = 안전한 상한)` 사용

4. `ChatScreen.kt` 설정에 이미지 분석 컨텍스트 토글을 추가한다.
   - 기존 Health/Phenotype 토글과 같은 UX
   - 전송 시점에 분석 결과를 읽고 promptMessages에 주입

5. `GalleryScreen.kt`에는 별도 “분석 실행” 버튼 또는 상태 영역을 추가한다.
   - 권한 상태
   - 분석 진행률
   - 분석 요약
   - LLM 주입용 prompt preview는 debug UI에서만 표시

6. 안전 응답 연결을 정한다.
   - `mustUseRuleBasedResponse`이면 LLM 자유 응답 전에 안전 정책을 우선 적용
   - 최소 1차 병합에서는 LLM prompt에 안전 policy block을 넣고, 강제 룰 응답은 후속 작업으로 분리해도 된다.

7. 검증한다.
   - `Frontend`에서 `compileDebugKotlin`
   - `assembleDebug`
   - 권한 없음, 부분 권한, 전체 권한 케이스
   - 이미지 0장, 소량, 대량 케이스
   - audit export off 확인

## 1차 병합 완료 기준

- 프론트 빌드가 성공한다.
- 갤러리 권한이 없을 때 채팅이 깨지지 않고 일반 상담 모드로 동작한다.
- 권한이 있을 때 이미지 분석 결과가 `ChatMessage` 목록에 시스템/컨텍스트 메시지로 주입된다.
- 프론트에서 원본 이미지를 별도로 저장하지 않는다.
- audit export는 기본 off다.
- LLM이 갤러리 분석을 직접 근거처럼 말하지 않도록 prompt guard가 들어간다.

