import type { Snapshot, Transaction } from 'dailyroll-native';

import {
  collectionLabel,
  mainAmount,
  parseWonInput,
  relativeTime,
  seoulTime,
  signedWon,
  statusMessage,
  tomorrowText,
  transactionAmountText,
  won,
} from '../src/lib/format';

const snap = (over: Partial<Snapshot> = {}): Snapshot => ({
  date: '2026-11-27',
  dailyAllowance: 12000,
  monthAccruedBudget: 324000,
  monthBudgetImpact: 0,
  available: 324000,
  overspent: 0,
  tomorrowAvailable: 336000,
  tomorrowStartsNewMonth: false,
  todaySpent: 0,
  monthGrossSpent: 0,
  monthRefunded: 0,
  monthNetSpent: 0,
  monthVaultDeposit: 0,
  monthVaultWithdrawal: 0,
  vaultBalance: 0,
  status: 'COMFORTABLE',
  ...over,
});

describe('금액 포맷', () => {
  it('천 단위 구분', () => {
    expect(won(0)).toBe('0원');
    expect(won(1234567)).toBe('1,234,567원');
    expect(won(-4500)).toBe('-4,500원');
  });

  it('음수는 초과로 표현', () => {
    expect(signedWon(-4500)).toBe('초과 4,500원');
    expect(signedWon(12000)).toBe('12,000원');
  });

  it('입력 파싱', () => {
    expect(parseWonInput('8,500')).toBe(8500);
    expect(parseWonInput('8500원')).toBe(8500);
    expect(parseWonInput('0')).toBeNull();
    expect(parseWonInput('0', true)).toBe(0);
    expect(parseWonInput('12.5')).toBeNull();
    expect(parseWonInput('-100')).toBeNull();
    expect(parseWonInput('abc')).toBeNull();
    expect(parseWonInput('9999999999')).toBeNull();
  });
});

describe('메인 금액 문구', () => {
  it('정상', () => {
    expect(mainAmount(snap())).toEqual({ label: '지금 쓸 수 있는 금액', amount: '324,000원', caption: '이번 달 이월 예산 포함' });
  });

  it('초과는 초과액으로', () => {
    const m = mainAmount(snap({ available: -4500, overspent: 4500, status: 'OVER' }));
    expect(m.label).toBe('예산 초과');
    expect(m.amount).toBe('4,500원');
  });

  it('0원', () => {
    expect(mainAmount(snap({ available: 0, status: 'CAUTION' })).caption).toBe('오늘은 남은 예산이 없어요');
  });

  it('내일 예상: 새 달 시작 표시', () => {
    expect(tomorrowText(snap({ tomorrowStartsNewMonth: true, tomorrowAvailable: 12000 }))).toBe(
      '내일(새 달 시작) 예상 사용 가능액 12,000원',
    );
    expect(tomorrowText(snap({ tomorrowAvailable: -100 }))).toBe('내일 예상 사용 가능액 초과 100원');
  });
});

describe('상태 문구', () => {
  it('권한 문제는 예산 상태보다 우선', () => {
    expect(statusMessage('COMFORTABLE', 'PERMISSION_REQUIRED')).toBe('결제 알림 연결을 확인해 주세요');
    expect(statusMessage('OVER', 'RECENTLY_DETECTED')).toBe('이번 달 예산을 초과했어요');
  });

  it('수집 상태 라벨', () => {
    expect(collectionLabel('WAITING')).toBe('연결됨 · 결제 알림 감지 대기');
    expect(collectionLabel('RECENTLY_DETECTED', 2)).toBe('최근 알림 감지됨 · 확인 필요 2건');
    expect(collectionLabel('PERMISSION_REQUIRED', 2)).toBe('권한 필요');
  });
});

describe('시간·거래 표시', () => {
  it('서울 기준 시각', () => {
    expect(seoulTime(Date.UTC(2026, 10, 27, 3, 30))).toBe('12:30');
    expect(seoulTime(Date.UTC(2026, 10, 27, 15, 5))).toBe('00:05');
  });

  it('상대 시간', () => {
    const now = Date.UTC(2026, 10, 27, 3, 0);
    expect(relativeTime(null, now)).toBe('기록 없음');
    expect(relativeTime(now - 30_000, now)).toBe('방금');
    expect(relativeTime(now - 5 * 60_000, now)).toBe('5분 전');
    expect(relativeTime(now - 3 * 3600_000, now)).toBe('3시간 전');
    expect(relativeTime(now - 50 * 3600_000, now)).toBe('2일 전');
  });

  it('승인은 −, 취소는 +', () => {
    const base = { amount: 8500, type: 'APPROVED' } as Transaction;
    expect(transactionAmountText(base)).toBe('−8,500원');
    expect(transactionAmountText({ ...base, type: 'CANCELED' })).toBe('+8,500원');
    expect(transactionAmountText({ ...base, amount: null })).toBe('금액 확인 필요');
  });
});
