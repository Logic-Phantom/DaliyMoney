import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import { DailyRoll, type MonthDay } from 'dailyroll-native';

import { won } from '@/lib/format';
import { useNative } from '@/lib/useDailyRoll';
import { Body, Card, Divider, Metric, SectionTitle } from '@/ui/components';
import { radius, space, tabular, useColors } from '@/ui/theme';

const WEEK = ['일', '월', '화', '수', '목', '금', '토'];

function currentSeoulMonth() {
  const d = new Date(Date.now() + 9 * 3600_000);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 };
}

export default function Report() {
  const c = useColors();
  const [ym, setYm] = useState(currentSeoulMonth);
  const { data, error } = useNative(() => DailyRoll!.getMonthReport(ym.year, ym.month), [ym.year, ym.month]);

  const shift = (delta: number) =>
    setYm(({ year, month }) => {
      const m = month + delta;
      if (m < 1) return { year: year - 1, month: 12 };
      if (m > 12) return { year: year + 1, month: 1 };
      return { year, month: m };
    });

  const now = currentSeoulMonth();
  const isCurrent = ym.year === now.year && ym.month === now.month;

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg, paddingBottom: space.xxl }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: space.md }}>
        <NavButton label="이전 달" symbol="‹" onPress={() => shift(-1)} />
        <Text accessibilityRole="header" style={{ color: c.text, fontSize: 20, fontWeight: '700' }}>
          {ym.year}년 {ym.month}월
        </Text>
        <NavButton label="다음 달" symbol="›" onPress={() => shift(1)} disabled={isCurrent} />
      </View>

      {!data ? (
        <Body secondary>{error ?? '불러오는 중…'}</Body>
      ) : (
        <>
          <Card>
            <Calendar days={data.days} />
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md, marginTop: space.md }}>
              <Legend symbol="○" text="무지출" color={c.good} />
              <Legend symbol="■" text="누적 예산 초과" color={c.over} />
              <Legend symbol="·" text="기록 전" color={c.textSecondary} />
            </View>
          </Card>

          <SectionTitle>{isCurrent ? '이번 달 (오늘까지)' : '월 마감'}</SectionTitle>
          <Card>
            <View style={{ flexDirection: 'row', gap: space.md }}>
              <Metric label="총 누적 예산" value={won(data.accruedBudget)} />
              <Metric label={isCurrent ? '남은 예산' : '월말 남은 예산'} value={data.remaining < 0 ? `초과 ${won(-data.remaining)}` : won(data.remaining)} />
            </View>
            <Divider />
            <View style={{ flexDirection: 'row', gap: space.md, marginTop: space.md }}>
              <Metric label="승인 총액" value={won(data.grossSpent)} />
              <Metric label="취소·환불" value={won(data.refunded)} />
              <Metric label="순 지출" value={won(data.netSpent)} />
            </View>
            <View style={{ flexDirection: 'row', gap: space.md, marginTop: space.md }}>
              <Metric label="금고 입금" value={won(data.vaultDeposit)} />
              <Metric label="금고 출금" value={won(data.vaultWithdrawal)} />
              <Metric label="금고 잔액" value={won(data.vaultBalanceAtEnd)} />
            </View>
            <View style={{ flexDirection: 'row', gap: space.md, marginTop: space.md }}>
              <Metric label="무지출 일수" value={`${data.noSpendDays}일`} />
              <Metric label={`${data.reward.name} 환산`} value={data.reward.valid ? `${data.reward.count}개` : '단가 설정 오류'} />
            </View>
            <Body secondary style={{ fontSize: 13, marginTop: space.md }}>
              월말 잔액과 초과액은 다음 달 예산에 자동으로 넘어가지 않아요. 남기고 싶은 금액은 금고에 잠가 두세요.
            </Body>
          </Card>
        </>
      )}
    </ScrollView>
  );
}

function NavButton({ label, symbol, onPress, disabled }: { label: string; symbol: string; onPress: () => void; disabled?: boolean }) {
  const c = useColors();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled}
      onPress={onPress}
      style={{ width: 48, height: 48, alignItems: 'center', justifyContent: 'center', opacity: disabled ? 0.3 : 1 }}
    >
      <Text style={{ color: c.text, fontSize: 28 }}>{symbol}</Text>
    </Pressable>
  );
}

function Legend({ symbol, text, color }: { symbol: string; text: string; color: string }) {
  const c = useColors();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <Text style={{ color, marginRight: 4 }}>{symbol}</Text>
      <Text style={{ color: c.textSecondary, fontSize: 13 }}>{text}</Text>
    </View>
  );
}

function Calendar({ days }: { days: MonthDay[] }) {
  const c = useColors();
  if (days.length === 0) return null;
  const first = new Date(`${days[0].date}T00:00:00Z`).getUTCDay();
  const cells: (MonthDay | null)[] = [...Array(first).fill(null), ...days];
  while (cells.length % 7 !== 0) cells.push(null);
  const rows = Array.from({ length: cells.length / 7 }, (_, i) => cells.slice(i * 7, i * 7 + 7));

  return (
    <View>
      <View style={{ flexDirection: 'row' }}>
        {WEEK.map((w) => (
          <Text key={w} style={{ flex: 1, textAlign: 'center', color: c.textSecondary, fontSize: 12, marginBottom: space.xs }}>
            {w}
          </Text>
        ))}
      </View>
      {rows.map((row, ri) => (
        <View key={ri} style={{ flexDirection: 'row' }}>
          {row.map((d, ci) => (
            <DayCell key={ci} d={d} />
          ))}
        </View>
      ))}
    </View>
  );
}

function DayCell({ d }: { d: MonthDay | null }) {
  const c = useColors();
  if (!d) return <View style={{ flex: 1, height: 58 }} />;
  const dom = Number(d.date.slice(8));
  let mark = '';
  let markColor = c.textSecondary;
  let desc = `${dom}일`;
  if (d.isFuture) {
    desc += ', 예정';
  } else if (d.isBeforeTracking && d.netSpent === 0) {
    mark = '·';
    desc += ', 기록 전';
  } else if (d.isOver) {
    mark = '■';
    markColor = c.over;
    desc += `, 순 지출 ${won(d.netSpent)}, 누적 예산 초과`;
  } else if (d.isNoSpend) {
    mark = '○';
    markColor = c.good;
    desc += ', 무지출';
  } else {
    desc += `, 순 지출 ${won(d.netSpent)}`;
  }
  const spent = !d.isFuture && d.netSpent > 0 ? compact(d.netSpent) : '';
  return (
    <View
      accessible
      accessibilityLabel={desc}
      style={{ flex: 1, height: 58, alignItems: 'center', paddingTop: 4, borderRadius: radius.sm }}
    >
      <Text style={{ color: d.isFuture ? c.textSecondary : c.text, fontSize: 14, fontWeight: '600' }}>{dom}</Text>
      <Text style={{ color: markColor, fontSize: 10, height: 14 }}>{mark}</Text>
      <Text style={[{ color: c.textSecondary, fontSize: 10 }, tabular]} numberOfLines={1}>
        {spent}
      </Text>
    </View>
  );
}

/** 캘린더 칸용 짧은 금액(예: 8.5천, 1.2만) */
function compact(n: number): string {
  if (n >= 10_000) return `${Math.round(n / 1_000) / 10}만`;
  if (n >= 1_000) return `${Math.round(n / 100) / 10}천`;
  return `${n}`;
}
