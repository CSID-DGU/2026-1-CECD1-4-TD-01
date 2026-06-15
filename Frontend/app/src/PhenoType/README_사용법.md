# 피노타입 분석 모듈 — 통화기록 & 앱 사용통계

다른 Android 프로젝트에 합쳐서 쓸 수 있는 독립 모듈입니다.

## 포함 파일

| 파일 | 역할 |
|---|---|
| `PhenotypeModels.kt` | 데이터 클래스 (분석 결과 담는 그릇) — **반드시 먼저 추가** |
| `CallLogAnalyzer.kt` | 통화기록 수집·분석 |
| `AppUsageAnalyzer.kt` | 앱 사용통계 수집·분석 |

---

## 1. 통합 방법

### (1) 파일 복사
3개 파일을 본인 프로젝트의 패키지 폴더로 복사한 뒤,
각 파일 맨 위의 `package` 선언을 본인 패키지명으로 바꾸세요.

```kotlin
// 예: 본인 패키지가 com.myapp.analysis 라면
package com.myapp.analysis
```

> `PhenotypeModels.kt`, `CallLogAnalyzer.kt`, `AppUsageAnalyzer.kt`
> 세 파일의 package 선언을 **모두 동일하게** 맞춰야 서로 참조됩니다.

### (2) AndroidManifest.xml 권한 추가

```xml
<!-- 통화기록: 런타임 요청 가능한 위험 권한 -->
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.READ_CONTACTS" />

<!-- 앱 사용통계: 일반 런타임 요청 불가 — 설정 화면 유도 필요 -->
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

> manifest 최상단 `<manifest>` 태그에 다음이 없으면 추가:
> `xmlns:tools="http://schemas.android.com/tools"`

### (3) 의존성 (build.gradle)
별도 외부 라이브러리 불필요. Kotlin Coroutines만 있으면 됩니다.

```gradle
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
```

---

## 2. 사용 예시

```kotlin
// 코루틴 스코프 안에서 (suspend 함수)
val callLogAnalyzer  = CallLogAnalyzer(context)
val appUsageAnalyzer = AppUsageAnalyzer(context)

// 통화기록 분석 (권한 없으면 null 반환)
val callSummary: CallLogSummary? = callLogAnalyzer.analyze()

// 앱 사용통계 분석 (권한 없으면 hasPermission=false 인 객체 반환)
val usageSummary: AppUsageSummary = appUsageAnalyzer.analyze()
```

---

## 3. 권한 요청 방법

### 통화기록 (런타임 권한)
```kotlin
val launcher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted -> /* granted == true 면 analyze() 호출 */ }

launcher.launch(Manifest.permission.READ_CALL_LOG)
```

### 앱 사용통계 (특수 권한 — 설정 화면으로 유도)
```kotlin
// 권한 확인
if (!appUsageAnalyzer.hasPermission()) {
    // 설정 화면 열기
    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    // 사용자가 ON 한 뒤 돌아오면 다시 analyze() 호출
}
```

---

## 4. 분석 결과 필드 설명

### CallLogSummary (통화기록)
| 필드 | 의미 |
|---|---|
| `totalCallsThisWeek` / `totalCallsPrevWeek` | 이번 주 / 지난 주 통화 수 |
| `uniqueContactsThisWeek` | 이번 주 고유 연락처 수 |
| `avgDurationSec` | 평균 통화 시간(초) |
| `missedCallRate` | 부재중 비율 (0.0~1.0) |
| `zeroCommunicationStreak` | 연속 무연락 일수 |
| `weeklyChangePct` | 주간 통화 변화율 (%) |
| `isolationAlert` | 대인교류 급감 경보 (전주 대비 50%↓) |
| `namedContactsRate` | 저장된 연락처로의 통화 비율 |

### AppUsageSummary (앱 사용통계)
| 필드 | 의미 |
|---|---|
| `topApps` | 사용시간 상위 앱 목록 (카테고리 포함) |
| `dailyAvgScreenTimeMin` | 일평균 스크린타임(분) |
| `dailyAvgLateNightMin` | 야간(00~06시) 일평균 사용(분) |
| `longestSingleSessionMin` | 최장 단일 연속 세션(분) |
| `weeklyChangePct` | 주간 스크린타임 변화율 (%) |
| `hasPermission` | 권한 허용 여부 |

---

## 5. 주의사항

- **iOS 불가**: 통화기록·앱 사용시간 접근은 Android 전용입니다.
- **minSdk 26 (Android 8.0)** 이상 권장 (`UsageStatsManager` 이벤트 기반 분석).
- 분석 기간은 코드 내 **최근 14일** 고정 (이번 주 vs 지난 주 비교용).
  기간을 바꾸려면 각 analyzer의 `oneWeekMs` 부분을 수정하세요.
