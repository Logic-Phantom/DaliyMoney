import type {
  BudgetInclusion,
  BudgetStatus,
  CollectionState,
  ReviewReason,
  Snapshot,
  Transaction,
  TransactionType,
} from 'dailyroll-native';

/** 원화 정수 포맷. 금액은 네이티브에서 Long(원) → number 로 넘어온다. 소수점은 다루지 않는다. */
export function won(value: number): string {
  const n = Math.trunc(value);
  const sign = n < 0 ? '-' : '';
  const digits = Math.abs(n).toString();
  return `${sign}${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}원`;
}

/** 음수는 "초과 4,500원" 으로 표시한다. "-4,500원 남음" 같은 표현을 쓰지 않는다. */
export function signedWon(value: number): string {
  return value < 0 ? `초과 ${won(-value)}` : won(value);
}

/** 입력창 문자열 → 원 단위 정수. 숫자가 아니거나 0 이하면 null */
export function parseWonInput(text: string, allowZero = false): number | null {
  const cleaned = text.replace(/[,\s원]/g, '');
  if (!/^\d{1,9}$/.test(cleaned)) return null;
  const n = Number(cleaned);
  if (!Number.isSafeInteger(n)) return null;
  if (allowZero ? n < 0 : n <= 0) return null;
  return n;
}

export interface MainAmount {
  label: string;
  amount: string;
  caption: string;
}

/** 대시보드 메인 카드 문구(위젯과 같은 규칙) */
export function mainAmount(s: Snapshot): MainAmount {
  if (s.available < 0) {
    return { label: '예산 초과', amount: won(s.overspent), caption: '이번 달 예산을 초과했어요' };
  }
  if (s.available === 0) {
    return { label: '지금 쓸 수 있는 금액', amount: won(0), caption: '오늘은 남은 예산이 없어요' };
  }
  return { label: '지금 쓸 수 있는 금액', amount: won(s.available), caption: '이번 달 이월 예산 포함' };
}

export function tomorrowText(s: Snapshot): string {
  const prefix = s.tomorrowStartsNewMonth ? '내일(새 달 시작) 예상 사용 가능액' : '내일 예상 사용 가능액';
  return `${prefix} ${signedWon(s.tomorrowAvailable)}`;
}

/** 소비 습관을 비난하지 않는 상태 문구 */
export function statusMessage(status: BudgetStatus, collection: CollectionState): string {
  if (collection === 'PERMISSION_REQUIRED' || collection === 'DISCONNECTED') return '결제 알림 연결을 확인해 주세요';
  switch (status) {
    case 'COMFORTABLE':
      return '이번 달 예산을 잘 지키고 있어요';
    case 'CAUTION':
      return '남은 예산을 확인해 보세요';
    case 'OVER':
      return '이번 달 예산을 초과했어요';
  }
}

export function collectionLabel(state: CollectionState, reviewCount = 0): string {
  const base: Record<CollectionState, string> = {
    NOT_SET_UP: '설정 전',
    PERMISSION_REQUIRED: '권한 필요',
    DISCONNECTED: '서비스 연결 끊김',
    WAITING: '연결됨 · 결제 알림 감지 대기',
    RECENTLY_DETECTED: '최근 알림 감지됨',
    NO_RECENT_DETECTION: '최근 감지 기록 없음',
  };
  return reviewCount > 0 && state !== 'PERMISSION_REQUIRED' && state !== 'DISCONNECTED'
    ? `${base[state]} · 확인 필요 ${reviewCount}건`
    : base[state];
}

export function isCollectionHealthy(state: CollectionState): boolean {
  return state === 'WAITING' || state === 'RECENTLY_DETECTED' || state === 'NO_RECENT_DETECTION';
}

export const TYPE_LABEL: Record<TransactionType, string> = {
  APPROVED: '승인',
  CANCELED: '취소',
  REFUND: '환불',
  UNKNOWN: '유형 미확인',
};

export const REASON_LABEL: Record<ReviewReason, string> = {
  MISSING_AMOUNT: '금액을 찾지 못했어요',
  MULTIPLE_AMOUNTS: '금액 후보가 여러 개예요',
  INVALID_AMOUNT: '금액이 비정상이에요',
  UNKNOWN_TYPE: '승인·취소 여부가 불분명해요',
  AMBIGUOUS_CANCEL: '결제 취소인지 확실하지 않아요',
  MISSING_MERCHANT: '가맹점명을 찾지 못했어요',
  AMBIGUOUS_YEAR: '결제 날짜(연도)가 모호해요',
  INVALID_DATE: '날짜를 해석할 수 없어요',
  UNMATCHED_REFUND: '원거래를 찾지 못한 취소·환불이에요',
  REFUND_EXCEEDS_ORIGINAL: '환불액이 원거래 잔액보다 커요',
  POSSIBLE_DUPLICATE: '중복 결제 알림일 수 있어요',
};

export function inclusionLabel(i: BudgetInclusion): string {
  switch (i) {
    case 'AUTO_INCLUDED':
      return '예산 포함';
    case 'USER_INCLUDED':
      return '예산 포함(직접 지정)';
    case 'AUTO_EXCLUDED':
      return '예산 제외(고정지출)';
    case 'USER_EXCLUDED':
      return '예산 제외(직접 지정)';
  }
}

export function sourceLabel(pkg: string): string {
  switch (pkg) {
    case 'com.kakaobank.channel':
      return '카카오뱅크';
    case 'com.kakao.talk':
      return '카카오톡';
    case 'com.samsung.android.spay':
      return '삼성월렛';
    case 'dailyroll.manual':
      return '직접 입력';
    case 'dailyroll.simulator':
      return '시뮬레이터';
    default:
      return pkg;
  }
}

/** 타임라인 금액 표시: 취소·환불은 +, 승인은 − */
export function transactionAmountText(t: Transaction): string {
  if (t.amount == null) return '금액 확인 필요';
  if (t.type === 'CANCELED' || t.type === 'REFUND') return `+${won(t.amount)}`;
  return `−${won(t.amount)}`;
}

const pad = (n: number) => n.toString().padStart(2, '0');

/** 서울 시간대 기준 HH:mm (네이티브와 같은 Asia/Seoul 기준으로 표시) */
export function seoulTime(ms: number): string {
  const d = new Date(ms + 9 * 3600_000);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

export function seoulDate(ms: number): string {
  const d = new Date(ms + 9 * 3600_000);
  return `${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일`;
}

export function seoulDateTime(ms: number): string {
  return `${seoulDate(ms)} ${seoulTime(ms)}`;
}

export function relativeTime(ms: number | null, now = Date.now()): string {
  if (ms == null) return '기록 없음';
  const diff = Math.max(0, now - ms);
  const min = Math.floor(diff / 60_000);
  if (min < 1) return '방금';
  if (min < 60) return `${min}분 전`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}시간 전`;
  return `${Math.floor(h / 24)}일 전`;
}

/** 중복 탭 방지용 요청 키 */
export function requestKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
