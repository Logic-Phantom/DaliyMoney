import { requireOptionalNativeModule, type EventSubscription } from 'expo-modules-core';

/** 네이티브 ↔ JS 계약. 계산·저장은 Kotlin(앱이 꺼져 있어도 리스너·위젯이 같은 엔진 사용), JS 는 화면만 그린다. */

export type CollectionState =
  | 'NOT_SET_UP'
  | 'PERMISSION_REQUIRED'
  | 'DISCONNECTED'
  | 'WAITING'
  | 'RECENTLY_DETECTED'
  | 'NO_RECENT_DETECTION';

export type BudgetStatus = 'COMFORTABLE' | 'CAUTION' | 'OVER';
export type TransactionType = 'APPROVED' | 'CANCELED' | 'REFUND' | 'UNKNOWN';
export type ReviewStatus = 'CONFIRMED' | 'NEEDS_REVIEW' | 'IGNORED' | 'DUPLICATE_CANDIDATE';
export type BudgetInclusion = 'AUTO_INCLUDED' | 'AUTO_EXCLUDED' | 'USER_INCLUDED' | 'USER_EXCLUDED';
export type ParseConfidence = 'HIGH' | 'MEDIUM' | 'LOW';
export type ReviewReason =
  | 'MISSING_AMOUNT'
  | 'MULTIPLE_AMOUNTS'
  | 'INVALID_AMOUNT'
  | 'UNKNOWN_TYPE'
  | 'AMBIGUOUS_CANCEL'
  | 'MISSING_MERCHANT'
  | 'AMBIGUOUS_YEAR'
  | 'INVALID_DATE'
  | 'UNMATCHED_REFUND'
  | 'REFUND_EXCEEDS_ORIGINAL'
  | 'POSSIBLE_DUPLICATE';

export interface Status {
  consented: boolean;
  permissionGranted: boolean;
  listenerConnected: boolean;
  collectionState: CollectionState;
  lastPaymentDetectedAt: number | null;
  listenerStateChangedAt: number | null;
  trackingStart: string | null;
  reviewCount: number;
  developerMode: boolean;
  sdkInt: number;
  today: string;
}

export interface Snapshot {
  date: string;
  dailyAllowance: number;
  monthAccruedBudget: number;
  monthBudgetImpact: number;
  available: number;
  overspent: number;
  tomorrowAvailable: number;
  tomorrowStartsNewMonth: boolean;
  todaySpent: number;
  monthGrossSpent: number;
  monthRefunded: number;
  monthNetSpent: number;
  monthVaultDeposit: number;
  monthVaultWithdrawal: number;
  vaultBalance: number;
  status: BudgetStatus;
}

export type Reward = { valid: true; name: string; count: number; unitPrice: number } | { valid: false; name: string };

export interface Transaction {
  id: number;
  sourcePackage: string;
  amount: number | null;
  type: TransactionType;
  merchantName: string | null;
  occurredAt: number;
  receivedAt: number;
  confidence: ParseConfidence;
  reviewStatus: ReviewStatus;
  reviewReasons: ReviewReason[];
  matchedTransactionId: number | null;
  budgetInclusion: BudgetInclusion;
  partialCancel: boolean;
  mergedSources: string[];
  isSimulated: boolean;
  userEdited: boolean;
}

export interface Dashboard {
  status: Status;
  snapshot: Snapshot;
  streakDays: number;
  reward: Reward;
  today: Transaction[];
  review: Transaction[];
}

export interface MonthDay {
  date: string;
  netSpent: number;
  availableAtEnd: number;
  isFuture: boolean;
  isBeforeTracking: boolean;
  isNoSpend: boolean;
  isOver: boolean;
}

export interface MonthReport {
  year: number;
  month: number;
  accruedBudget: number;
  grossSpent: number;
  refunded: number;
  netSpent: number;
  vaultDeposit: number;
  vaultWithdrawal: number;
  vaultBalanceAtEnd: number;
  remaining: number;
  noSpendDays: number;
  reward: Reward;
  days: MonthDay[];
}

export interface Settings {
  dailyAllowance: number;
  allowanceHistory: { effectiveFrom: string; amount: number }[];
  rewardName: string;
  rewardUnitPrice: number;
  hideWidgetAmounts: boolean;
  developerMode: boolean;
}

export interface ExclusionRule {
  id: number;
  keyword: string;
  enabled: boolean;
}

export interface TransactionEdit {
  amount?: number;
  merchantName?: string;
  type?: TransactionType;
  occurredAt?: number;
}

export interface ParseTestResult {
  result: 'PAYMENT' | 'IGNORED' | 'NEEDS_REVIEW';
  amount: number | null;
  transactionType: TransactionType | null;
  merchantName: string | null;
  occurredAt: number | null;
  confidence: ParseConfidence;
  reasons: ReviewReason[];
  partialCancel: boolean;
  autoApplicable: boolean;
}

export type SimulationKind = 'APPROVE' | 'CANCEL_FULL' | 'CANCEL_PARTIAL' | 'REFUND' | 'SAME_AMOUNT_TWICE' | 'FIXED_COST';

export interface DailyRollNative {
  getStatus(): Promise<Status>;
  giveConsent(): Promise<Status>;
  openNotificationListenerSettings(): Promise<boolean>;
  openAppDetailsSettings(): Promise<boolean>;
  getDashboard(): Promise<Dashboard>;
  getSnapshotFor(isoDate: string): Promise<Snapshot>;
  getMonthReport(year: number, month: number): Promise<MonthReport>;
  listTransactions(fromMs: number, toMs: number): Promise<Transaction[]>;
  getTransaction(id: number): Promise<Transaction | null>;
  addManualExpense(amount: number, merchant: string | null, occurredAtMs: number, requestKey: string): Promise<number | null>;
  confirmTransaction(id: number, edit: TransactionEdit | null, matchedId: number | null): Promise<void>;
  updateTransaction(id: number, edit: TransactionEdit): Promise<void>;
  ignoreTransaction(id: number): Promise<void>;
  setInclusion(id: number, include: boolean): Promise<void>;
  vaultDeposit(amount: number, requestKey: string): Promise<void>;
  vaultWithdraw(amount: number, requestKey: string): Promise<void>;
  getSettings(): Promise<Settings>;
  setDailyAllowance(amount: number): Promise<Settings>;
  setReward(name: string, price: number): Promise<Settings>;
  setHideWidgetAmounts(hide: boolean): Promise<Settings>;
  setDeveloperMode(enabled: boolean): Promise<Settings>;
  listExclusionRules(): Promise<ExclusionRule[]>;
  addExclusionRule(keyword: string): Promise<void>;
  setExclusionRuleEnabled(id: number, enabled: boolean): Promise<void>;
  deleteExclusionRule(id: number): Promise<void>;
  previewExclusionReapply(): Promise<(Transaction & { nextInclusion: BudgetInclusion })[]>;
  applyExclusionReapply(): Promise<number>;
  wipeAllData(): Promise<void>;
  refreshWidgets(): Promise<void>;
  testParse(sourcePackage: string, title: string | null, text: string): Promise<ParseTestResult>;
  knownSources(): Promise<{ packageName: string; label: string }[]>;
  simulate(kind: SimulationKind, amount: number, merchant: string): Promise<string[]>;
  addListener(event: 'onChange', listener: () => void): EventSubscription;
}

const native = requireOptionalNativeModule<DailyRollNative>('DailyRoll');

/** Expo Go·웹 등 네이티브 모듈이 없는 환경이면 null. 화면은 이 경우 안내 문구를 보여준다. */
export const DailyRoll: DailyRollNative | null = native;

export function isNativeAvailable(): boolean {
  return native != null;
}
