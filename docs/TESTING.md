# 테스트·검증 기록

실행하지 않은 항목을 성공으로 적지 않는다. 날짜는 실제 실행일.

## 자동 테스트

| 구분 | 명령 | 범위 |
|---|---|---|
| 코어(JVM) | `npm run test:core` | 예산 계산·이력·인사이트·환불 매칭·제외 규칙·파서·날짜·중복·위젯 표시 규칙 (82개) |
| 코어(Android) | `npm run test:native` | 위와 동일 테스트를 Android 라이브러리 모듈에서 실행 |
| JS | `npm test` · `npm run typecheck` · `npm run lint` | 금액 포맷·문구 규칙, 타입, ESLint |
| 빌드 | `npm run android:lint` · `npm run android:debug` | 네이티브 모듈 Lint, Debug APK |

파서 fixture(`KakaoNotificationParserTest`)는 기획서 예시 기반 **가정 문자열**이다. 실제 기기에서 받은 알림(이름·카드번호 마스킹)을
앱의 `설정 > 파싱 테스트`로 확인한 뒤 fixture 로 추가한다.

## 실기기 QA 체크리스트 (Samsung Galaxy / One UI) — 아직 수행 전

- [ ] EAS preview APK 설치 → 온보딩 고지 → `동의하고 알림 권한 설정` → 알림 접근 허용 → 복귀 시 `연결됨` 표시
- [ ] APK 직접 설치 시 `제한된 설정 허용` 필요 여부와 메뉴 위치 기록
- [ ] 실제 카카오뱅크 체크카드 승인 알림 → 홈·위젯 반영 (패키지명 `com.kakaobank.channel` 확인)
- [ ] 카카오톡 알림톡 승인 알림, 삼성월렛 결제 알림 (같은 결제가 두 출처로 오면 병합 또는 중복 의심)
- [ ] 승인 직후 전체 취소, 부분 취소, 다음 날·다음 달 도착한 취소
- [ ] 같은 금액·같은 가맹점 연속 결제 2건 모두 기록
- [ ] 알림 권한 해제 → 앱·위젯에 `권한 필요` 표시 → 재허용 후 복구
- [ ] 앱 강제 종료·재부팅 후 결제 알림 수신, DB 유지
- [ ] 위젯 2x1·4x1·4x2 및 리사이즈, 라이트·다크, 새로고침, `+` → 지출 입력 화면
- [ ] 자정 이후 위젯 날짜·금액 갱신(지연 허용 범위 기록), 월말 → 월초 전환
- [ ] 위젯 금액 숨기기, TalkBack 으로 금액·상태 읽기, 큰 글꼴
- [ ] `adb logcat | findstr DailyRoll` 로 금액·가맹점이 로그에 없는지 확인
- [ ] 릴리스 APK 에 INTERNET 권한이 없는지: `aapt dump permissions app-release.apk`

특정 갤럭시 모델에서의 결과를 모든 Android 기기에서 보장한다고 표현하지 않는다.
