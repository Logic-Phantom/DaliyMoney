# CLAUDE.md — DailyRoll

- 사용자와는 **한국어**로만 소통한다. 작업 후 README 의 `작업 내역`을 갱신하고 `origin main`에 푸시한다.
- 기획 기준 문서: `docs/PLANNING_NOTES.md`(원 기획서 대비 결정·변경 사항). 원 기획서의 결제 알림 예시는 fixture 일 뿐 공식 규격이 아니다.

## 구조
- **Expo(React Native) + Kotlin 로컬 모듈** — EAS Build 로 APK/AAB 를 만든다.
- `modules/dailyroll-native/android/src/main/java/com/dailyroll/core/`
  - `domain/`, `money/` — **순수 Kotlin**(android.* import 금지). 예산 계산·원장·파서·중복·위젯 표시 규칙. 테스트 필수.
  - `data/` — SQLiteOpenHelper 저장소. 마이그레이션은 `DailyRollDatabase.MIGRATIONS`에 **새 버전 추가**(기존 버전 수정·destructive 금지).
  - `service/` — NotificationListenerService, `widget/` — RemoteViews 위젯, `DailyRollModule.kt` — JS 브리지.
- `src/app` — expo-router 화면(홈·리포트·설정 3탭 유지). UI 는 `dailyroll-native` 모듈만 호출한다. 계산을 JS 로 복제하지 않는다.
- `plugins/withDailyRollPrivacy.js` — 릴리스 INTERNET 권한 제거, 백업·기기 이전 제외.

## 명령
- 코어 테스트(빠름, Android SDK 불필요): `npm run test:core` (= `tools/core-jvm/gradlew test`)
- JS: `npm run typecheck` · `npm run lint` · `npm test`
- 네이티브: `npm run prebuild` → `npm run test:native` · `npm run android:lint` · `npm run android:debug`
  (`scripts/android-build.ps1`, 기본 JDK `C:\Android\jdk-17`, SDK `C:\Android\Sdk`)
- 클라우드 빌드: `npx eas-cli build -p android --profile preview` (APK, 로그인 필요)

## 제품·보안 원칙(변경 금지)
- 은행 로그인·금융 API·서버·계정·광고/분석 SDK·클라우드 AI 추가 금지. 릴리스 빌드는 네트워크 권한이 없다.
- 알림 원문을 DB·로그에 저장하지 않는다. 로그에 금액·가맹점·카드 정보 출력 금지(`NotificationText.toString()`은 마스킹).
- 허용 목록(`NotificationSourceRegistry`) 밖 알림은 읽지 않는다. 확신이 낮으면 `NEEDS_REVIEW` — 추측으로 예산 반영 금지.
- "금액+유형이 같으면 중복" 규칙 금지(같은 금액 연속 결제 보존). 금액은 Long 원 단위, Float/Double 금지.
- 권한 해제·연결 끊김을 정상처럼 보여주지 않는다. 실행하지 않은 테스트·실기기 검증을 성공으로 기록하지 않는다(`docs/TESTING.md`).
