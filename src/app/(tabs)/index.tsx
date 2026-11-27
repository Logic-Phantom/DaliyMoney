import { useRouter } from 'expo-router';
import { Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';

import { DailyRoll, type Transaction } from 'dailyroll-native';

import {
  REASON_LABEL,
  TYPE_LABEL,
  collectionLabel,
  inclusionLabel,
  isCollectionHealthy,
  mainAmount,
  seoulDateTime,
  seoulTime,
  sourceLabel,
  statusMessage,
  tomorrowText,
  transactionAmountText,
  won,
} from '@/lib/format';
import { useNative } from '@/lib/useDailyRoll';
import { Amount, Body, Button, Card, Divider, ListRow, Metric, SectionTitle, StatusPill } from '@/ui/components';
import { space, useColors } from '@/ui/theme';

export default function Dashboard() {
  const c = useColors();
  const router = useRouter();
  const { data, loading, error, reload } = useNative(() => DailyRoll!.getDashboard());

  if (!data) {
    return (
      <View style={{ flex: 1, padding: space.xl, backgroundColor: c.bg }}>
        <Body secondary>{error ?? '불러오는 중…'}</Body>
      </View>
    );
  }

  const { snapshot: s, status, review, today, streakDays, reward } = data;
  const main = mainAmount(s);
  const tone = !isCollectionHealthy(status.collectionState) ? 'over' : s.status === 'OVER' ? 'over' : s.status === 'CAUTION' ? 'caution' : 'good';
  const amountColor = s.available < 0 ? c.over : c.text;

  return (
    <View style={{ flex: 1, backgroundColor: c.bg }}>
      <ScrollView
        contentContainerStyle={{ padding: space.lg, paddingBottom: 120 }}
        refreshControl={<RefreshControl refreshing={loading} onRefresh={reload} />}
      >
        {/* 수집 상태: 권한이 꺼져 있으면 정상처럼 보이지 않게 맨 위에 표시 */}
        <Pressable accessibilityRole="button" onPress={() => router.push('/settings')} style={{ marginBottom: space.md }}>
          <StatusPill
            tone={isCollectionHealthy(status.collectionState) ? (status.reviewCount > 0 ? 'caution' : 'neutral') : 'over'}
            text={`${Number(s.date.slice(5, 7))}월 ${Number(s.date.slice(8))}일 · ${collectionLabel(status.collectionState, status.reviewCount)}`}
          />
        </Pressable>

        <Card>
          <Body secondary>{main.label}</Body>
          <Amount size={40} color={amountColor} style={{ marginTop: space.xs }}>
            {main.amount}
          </Amount>
          <View style={{ marginTop: space.sm }}>
            <StatusPill tone={tone} text={`${main.caption} · ${statusMessage(s.status, status.collectionState)}`} />
          </View>
          <Divider />
          <Body style={{ marginTop: space.md }}>{tomorrowText(s)}</Body>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md, marginTop: space.lg }}>
            <Metric label="이번 달 순 지출" value={won(s.monthNetSpent)} />
            <Metric label="누적 기본 예산" value={won(s.monthAccruedBudget)} />
            <Metric label="오늘 지출" value={won(s.todaySpent)} />
          </View>
        </Card>

        <Card>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <View style={{ flex: 1 }}>
              <Body secondary>금고(앱 안의 가상 저축 기록)</Body>
              <Amount size={22}>{won(s.vaultBalance)}</Amount>
              <Body secondary style={{ fontSize: 13 }}>이번 달 잠금 {won(s.monthVaultDeposit)}</Body>
            </View>
            <View style={{ width: 130 }}>
              <Button title="금고에 잠그기" kind="secondary" onPress={() => router.push('/vault')} />
            </View>
          </View>
          <View style={{ flexDirection: 'row', gap: space.md, marginTop: space.md }}>
            <Metric label="무지출" value={streakDays > 0 ? `${streakDays}일째` : '—'} />
            <Metric label={`${reward.name} 환산`} value={reward.valid ? `${reward.count}개` : '단가 설정 오류'} />
          </View>
        </Card>

        {review.length > 0 ? (
          <>
            <SectionTitle>확인 필요 {review.length}건</SectionTitle>
            <Card>
              <Body secondary style={{ fontSize: 13, marginBottom: space.xs }}>
                확실하지 않아 예산에 아직 반영하지 않은 거래예요. 눌러서 확인해 주세요.
              </Body>
              {review.map((t, i) => (
                <View key={t.id}>
                  {i > 0 ? <Divider /> : null}
                  <ReviewRow t={t} onPress={() => router.push(`/transaction/${t.id}`)} />
                </View>
              ))}
            </Card>
          </>
        ) : null}

        <SectionTitle>오늘 결제</SectionTitle>
        <Card>
          {today.length === 0 ? (
            <Body secondary>
              {isCollectionHealthy(status.collectionState)
                ? '오늘 기록된 결제가 없어요. 결제했는데 보이지 않으면 설정 > 파싱 테스트로 확인해 보세요.'
                : '결제 알림 권한이 꺼져 있어 자동 기록이 되지 않아요.'}
            </Body>
          ) : (
            today.map((t, i) => (
              <View key={t.id}>
                {i > 0 ? <Divider /> : null}
                <TimelineRow t={t} onPress={() => router.push(`/transaction/${t.id}`)} />
              </View>
            ))
          )}
        </Card>
      </ScrollView>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="지출 직접 입력"
        onPress={() => router.push('/expense/new')}
        style={({ pressed }) => ({
          position: 'absolute',
          right: space.xl,
          bottom: space.xl,
          width: 60,
          height: 60,
          borderRadius: 30,
          backgroundColor: c.accent,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: pressed ? 0.8 : 1,
          elevation: 4,
        })}
      >
        <Text style={{ color: c.onAccent, fontSize: 30, lineHeight: 34 }}>+</Text>
      </Pressable>
    </View>
  );
}

function TimelineRow({ t, onPress }: { t: Transaction; onPress: () => void }) {
  const c = useColors();
  const excluded = t.budgetInclusion === 'AUTO_EXCLUDED' || t.budgetInclusion === 'USER_EXCLUDED';
  const ignored = t.reviewStatus === 'IGNORED';
  const sub = [
    seoulTime(t.occurredAt),
    sourceLabel(t.sourcePackage),
    TYPE_LABEL[t.type],
    ignored ? '무시됨' : inclusionLabel(t.budgetInclusion),
    t.isSimulated ? '가상' : null,
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <ListRow
      title={t.merchantName ?? '가맹점 미확인'}
      subtitle={sub}
      right={transactionAmountText(t)}
      rightColor={ignored || excluded ? c.textSecondary : t.type === 'APPROVED' ? c.text : c.good}
      onPress={onPress}
      accessibilityLabel={`${t.merchantName ?? '가맹점 미확인'}, ${transactionAmountText(t)}, ${sub}`}
    />
  );
}

function ReviewRow({ t, onPress }: { t: Transaction; onPress: () => void }) {
  const c = useColors();
  const reason = t.reviewReasons.map((r) => REASON_LABEL[r]).join(', ') || '확인이 필요해요';
  return (
    <ListRow
      title={`${t.merchantName ?? '가맹점 미확인'} · ${TYPE_LABEL[t.type]}`}
      subtitle={`${seoulDateTime(t.occurredAt)} · ${sourceLabel(t.sourcePackage)} · ${reason}`}
      right={t.amount == null ? '금액?' : won(t.amount)}
      rightColor={c.caution}
      onPress={onPress}
    />
  );
}
