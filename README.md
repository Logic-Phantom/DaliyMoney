# DailyRoll

카카오뱅크 체크카드 등의 **결제 알림을 스마트폰 안에서만 읽어** 지출을 기록하고, 하루 예산의 남은 금액을 **이번 달 안에서 다음 날로 이월**해
보여주는 Android 홈 화면 위젯 앱.

> 로컬 우선 · 무료 · 최소 권한 · 결제 정보 보호 — 서버·회원가입·은행 로그인·광고/분석 SDK 없음, 배포용 빌드에는 인터넷 권한 자체가 없음.

## 핵심 규칙

- 하루 기본 예산(기본 12,000원)이 매일 누적된다. `지금 쓸 수 있는 금액 = 이번 달 1일~오늘 누적 예산 − 이번 달 원장 합계`
- 매월 1일 새로 시작한다. 월말 잔액·초과액은 다음 달로 넘어가지 않는다(남기고 싶으면 금고에 잠금).
- 내일 예상액은 `오늘 잔액 + 하루 예산`이 아니라 **내일 날짜로 엔진을 다시 계산**한다(말일 → 새 달 12,000원).
- 모든 예산 변동은 원장(PURCHASE / REFUND / VAULT_DEPOSIT / VAULT_WITHDRAWAL / ADJUSTMENT)으로 기록. 금고 입금은 소비가 아니다.
- 확신이 낮은 거래(금액 후보 여러 개, 가맹점 없음, 원거래 없는 취소, 모호한 연도, 중복 의심)는 예산에 넣지 않고 **확인 필요**로 보낸다.
- 하루 예산을 바꾸면 **오늘부터** 적용된다(지난 날짜 소급 없음).

## 구조

```text
DaliyMoney/
├── app.json · eas.json              # Expo 설정 · EAS Build 프로필(development / preview=APK / production=AAB)
├── plugins/withDailyRollPrivacy.js  # 릴리스 INTERNET 권한 제거, 클라우드 백업·기기 이전 제외
├── modules/dailyroll-native/        # Expo 로컬 모듈 (Kotlin) — 계산·파싱·저장의 진실의 원천
│   ├── src/index.ts                 # JS 타입·바인딩
│   └── android/src/main/java/com/dailyroll/core/
│       ├── domain/                  # 순수 Kotlin(android.* 없음)
│       │   ├── budget/              # BudgetCalculator, AllowanceSchedule, 스트릭·보상·월간 리포트
│       │   ├── transaction/         # 모델, RefundMatcher, ExclusionPolicy
│       │   ├── notification/        # NotificationText, SourceRegistry, KakaoNotificationParser, 날짜 추정, Deduplicator
│       │   └── widget/              # WidgetPresenter(위젯 상태·문구 규칙)
│       ├── money/MoneyFormatter.kt
│       ├── data/                    # SQLite(DailyRollDatabase·마이그레이션), LedgerRepository, SettingsStore
│       ├── service/                 # PaymentNotificationListener, NotificationTextExtractor
│       ├── widget/                  # DailyRollWidgetProvider (RemoteViews 반응형 2x1·4x1·4x2)
│       ├── app/AppContainer.kt      # 수동 DI
│       └── DailyRollModule.kt       # JS 브리지
├── src/
│   ├── app/                         # expo-router 화면: (tabs) 홈·리포트·설정, onboarding, expense/new, vault,
│   │                                #   transaction/[id], tools/parser, exclusions
│   ├── lib/                         # format(금액·문구), useNative(포커스·복귀·onChange 자동 갱신)
│   └── ui/                          # 디자인 토큰·공용 컴포넌트
├── tools/core-jvm/                  # 코어 Kotlin 을 Android SDK 없이 테스트하는 독립 Gradle 빌드
├── tests/                           # Jest
└── docs/                            # PLANNING_NOTES(설계 결정), TESTING(검증 기록·실기기 QA)
```

앱이 꺼져 있어도 알림 리스너와 위젯이 같은 엔진을 써야 하므로 계산·저장은 Kotlin 에 있고, JS 는 결과를 그리기만 한다.
기획서 대비 바꾼 점과 이유는 [docs/PLANNING_NOTES.md](docs/PLANNING_NOTES.md) 참고.

## 개발 명령

```powershell
npm install
npm run test:core        # 코어 Kotlin 단위 테스트(빠름, Android SDK 불필요)
npm test                 # Jest
npm run typecheck
npm run lint
npm run prebuild         # android/ 생성(커밋하지 않음, EAS 가 클라우드에서 다시 생성)
npm run test:native      # 같은 코어 테스트를 Android 모듈에서
npm run android:lint     # 네이티브 모듈 Android Lint
npm run android:debug    # Debug APK (scripts/android-build.ps1: JDK C:\Android\jdk-17, SDK C:\Android\Sdk)
```

### EAS Build (클라우드)

```powershell
npx eas-cli login
npx eas-cli build -p android --profile preview      # 설치용 APK (릴리스 빌드, 인터넷 권한 없음)
npx eas-cli build -p android --profile production   # Play 제출용 AAB
```

처음 빌드할 때 EAS 가 프로젝트 ID 를 `app.json` 에 추가하고 키스토어 생성을 묻는다. Expo Go 에서는 네이티브 모듈이 없어 동작하지 않는다.

## 지원 범위

- Android 8.0(API 26) 이상, 우선 검증 기기 Samsung Galaxy / One UI
- compileSdk 37 · targetSdk 36 (Expo SDK 58 / React Native 0.88 기본값, Google Play 요구 API 36 이상)
- 알림 출처 허용 목록(실기기 검증 전 후보값): 카카오뱅크 `com.kakaobank.channel`, 카카오톡 `com.kakao.talk`, 삼성월렛 `com.samsung.android.spay`

## 작업 내역

### 2026-11-27 — 프로젝트 구조·아키텍처 초기 구현 (v0.1.0)

**구현 완료**
- Expo SDK 58 + React Native 0.88 프로젝트, EAS Build 프로필(development·preview APK·production AAB), minSdk 26
- 순수 Kotlin 예산 엔진: 월 단위 이월, 다음 날 재계산(월말 → 새 달), 음수 유지, 원장 이벤트 부호 규칙, 금고, 예산 변경 이력
- 무지출 스트릭(월 경계 넘음, 수집 시작 이전은 추정 안 함), 보상 환산(단가 오류 처리), 월간 리포트
- 결제 알림 파서: 필드별 추출(TITLE/TEXT/BIG_TEXT/TEXT_LINES/MessagingStyle), 다중 금액 후보·비결제 금액 문맥 제외, 승인/취소/부분취소/환불 구분,
  모호한 취소 확인 대기, 가맹점 토큰 제거 추출, 연도 없는 날짜 추정, 오탐 방지(광고·잔액·인증·결제 예정·발급·배송·입출금·일반 대화)
- 중복 처리: 같은 알림 재게시 idempotent, 같은 금액 연속 결제 보존, 다른 출처 강한 일치만 병합(출처 메타데이터 보존), 애매하면 중복 의심
- 취소·환불 원거래 연결: 미환불 잔액 검증, 부분 취소 누적, 원거래 없으면 자동 복구 안 함
- 고정지출 제외 키워드(기본 시드, 짧은 영문 단어 경계), 사용자 지정 우선, 소급 적용은 미리보기 후 명시 실행
- SQLite 저장소: 거래·원장·제외 규칙·예산 이력·정기구독(v1 스키마), UNIQUE 기반 idempotent, 단일 트랜잭션, 인덱스, 원본 파싱값 별도 보관
- NotificationListenerService: 허용 목록·앱 내 동의 이후만 처리, 백그라운드 단일 스레드, 연결/해제 상태 기록·재바인드 요청, 민감정보 없는 로그
- 홈 위젯(RemoteViews): 컴팩트·와이드(사용량 바)·메인(헤더·내일 예상·스트릭·계산 시각), 라이트/다크, 권한 없음·연결 끊김·확인 필요·0원·초과·
  금액 숨기기·초기 설정 전 상태, 새로고침·`+`(지출 입력)·대시보드 이동, 자정 부정확 알람·시간대 변경 갱신
- 화면: 온보딩(목적·처리 항목·저장 위치·외부 전송·한계 고지 후 명시적 동의), 홈 대시보드(수집 상태·메인 금액·내일 예상·금고·스트릭·보상·
  확인 필요·오늘 타임라인·FAB), 거래 확인·수정·예산 포함/제외·무시, 수동 지출, 금고 잠금/해제, 월간 리포트(캘린더 기호+접근성 설명),
  설정(권한·마지막 감지·하루 예산·보상·위젯 금액 숨기기·데이터 전체 삭제·개발자 모드), 파싱 테스트·시뮬레이터, 제외 키워드 관리
- 개인정보: 릴리스 INTERNET 권한 제거, `allowBackup=false` + dataExtractionRules, 알림 원문 미저장, `NotificationText.toString()` 마스킹
- CI(GitHub Actions): 코어 Kotlin 테스트 + typecheck·lint·Jest

**실행한 명령과 실제 결과**

| 명령 | 결과 |
|---|---|
|  (tools/core-jvm) | 통과 — 82개 |
|  (Android 모듈, Robolectric 저장소 테스트 17개 포함) | 통과 — 99개 |
|  (:dailyroll-native:lintDebug) | 오류 0, 경고 31(KTX 권장·하드코딩 문자열·작은 sp 등) |
|  ·  · Unknown command: "test"


Did you mean this?
  npm test # Test a package
To see a list of supported npm commands, run:
  npm help | 통과 (Jest 12개) |
|  | **성공** — APK 91MB, targetSdk 36 / compileSdk 37 |
|  (preview APK) | INTERNET 권한 없음 확인, allowBackup=false·dataExtractionRules·리스너·위젯 포함 |
| 로컬  (Windows) | 실패 — RN 서드파티 C++ 경로가 Windows 260자 제한 초과(코드 문제 아님). APK 는 EAS 로 빌드 |

- 빌드 중 수정: expo-router 의 선택적 의존성 (Drawer 전용, 미사용)를 autolinking 에서 제외, 미사용  권한 제거
- EAS 프로젝트:  (원격 키스토어 사용)

**미완료 / 다음 우선순위**
1. 실제 갤럭시 기기 QA(`docs/TESTING.md` 체크리스트) — 실제 알림 문구로 파서 fixture 보강, 패키지명 확정
2. Lint 경고 정리, 실기기에서 위젯 크기·다크 모드 확인 후 레이아웃 조정
3. 정기구독 CRUD·D-3 알림(WorkManager, POST_NOTIFICATIONS 요청), CSV/JSON 내보내기
4. 앱 아이콘·스플래시 디자인
