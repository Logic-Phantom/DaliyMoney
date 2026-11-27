import { useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, Pressable, ScrollView, Text, View } from 'react-native';

import { DailyRoll, type TransactionEdit, type TransactionType } from 'dailyroll-native';

import { REASON_LABEL, TYPE_LABEL, inclusionLabel, parseWonInput, seoulDateTime, sourceLabel } from '@/lib/format';
import { errorMessage, useNative } from '@/lib/useDailyRoll';
import { Body, Button, Card, Divider, Field, ListRow, StatusPill } from '@/ui/components';
import { radius, space, useColors } from '@/ui/theme';

const TYPES: TransactionType[] = ['APPROVED', 'CANCELED', 'REFUND'];

/** 거래 상세·수정 + 확인 필요 거래 처리 */
export default function TransactionDetail() {
  const c = useColors();
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const txId = Number(id);
  const { data: t, reload } = useNative(() => DailyRoll!.getTransaction(txId), [txId]);
  const [amount, setAmount] = useState('');
  const [merchant, setMerchant] = useState('');
  const [type, setType] = useState<TransactionType>('APPROVED');
  const [busy, setBusy] = useState(false);
  const [syncedFrom, setSyncedFrom] = useState<typeof t>(null);

  // 불러온 거래로 입력창을 채운다(렌더 중 상태 조정 패턴)
  if (t && t !== syncedFrom) {
    setSyncedFrom(t);
    setAmount(t.amount == null ? '' : String(t.amount));
    setMerchant(t.merchantName ?? '');
    setType(t.type === 'UNKNOWN' ? 'APPROVED' : t.type);
  }

  if (!t) {
    return (
      <View style={{ padding: space.lg }}>
        <Body secondary>불러오는 중…</Body>
      </View>
    );
  }

  const pending = t.reviewStatus === 'NEEDS_REVIEW' || t.reviewStatus === 'DUPLICATE_CANDIDATE';
  const parsedAmount = parseWonInput(amount);

  const edit = (): TransactionEdit => {
    const e: TransactionEdit = {};
    if (parsedAmount != null && parsedAmount !== t.amount) e.amount = parsedAmount;
    if (merchant.trim() !== (t.merchantName ?? '')) e.merchantName = merchant.trim();
    if (type !== t.type) e.type = type;
    return e;
  };

  const act = async (fn: () => Promise<void>, back = true) => {
    setBusy(true);
    try {
      await fn();
      if (back) router.back();
      else reload();
    } catch (e) {
      Alert.alert('처리하지 못했어요', errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const excluded = t.budgetInclusion === 'AUTO_EXCLUDED' || t.budgetInclusion === 'USER_EXCLUDED';

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg, paddingBottom: space.xxl }} keyboardShouldPersistTaps="handled">
      {pending ? (
        <Card>
          <StatusPill tone="caution" text={t.reviewStatus === 'DUPLICATE_CANDIDATE' ? '중복 의심 · 예산 미반영' : '확인 필요 · 예산 미반영'} />
          {t.reviewReasons.map((r) => (
            <Body key={r} secondary style={{ fontSize: 14, marginTop: space.xs }}>
              · {REASON_LABEL[r]}
            </Body>
          ))}
        </Card>
      ) : null}

      <Card>
        <ListRow title="출처" right={sourceLabel(t.sourcePackage) + (t.isSimulated ? ' (가상)' : '')} />
        {t.mergedSources.length > 0 ? (
          <ListRow title="같은 거래로 합친 알림" right={t.mergedSources.map(sourceLabel).join(', ')} />
        ) : null}
        <ListRow title="결제 시각" right={seoulDateTime(t.occurredAt)} />
        <ListRow title="인식 신뢰도" right={{ HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }[t.confidence]} />
        <ListRow title="예산" right={t.reviewStatus === 'IGNORED' ? '무시됨' : inclusionLabel(t.budgetInclusion)} />
        {t.userEdited ? <Body secondary style={{ fontSize: 12 }}>사용자가 수정한 값이에요(원래 인식값은 따로 보관).</Body> : null}
      </Card>

      <Field label="금액(원)" keyboardType="number-pad" value={amount} onChangeText={setAmount} error={amount && parsedAmount == null ? '1원 이상 정수' : null} />
      <Field label="가맹점" value={merchant} onChangeText={setMerchant} maxLength={40} placeholder="가맹점 미확인" />
      <Text style={{ color: c.textSecondary, fontSize: 13, marginBottom: space.xs }}>거래 유형</Text>
      <View style={{ flexDirection: 'row', gap: space.sm, marginBottom: space.md }}>
        {TYPES.map((k) => (
          <Pressable
            key={k}
            accessibilityRole="radio"
            accessibilityState={{ selected: type === k }}
            onPress={() => setType(k)}
            style={{
              flex: 1,
              minHeight: 48,
              borderRadius: radius.sm,
              alignItems: 'center',
              justifyContent: 'center',
              borderWidth: 1,
              borderColor: type === k ? c.text : c.border,
              backgroundColor: type === k ? c.surface : 'transparent',
            }}
          >
            <Text style={{ color: c.text, fontWeight: type === k ? '700' : '400' }}>{TYPE_LABEL[k]}</Text>
          </Pressable>
        ))}
      </View>

      {pending ? (
        <>
          <Button
            title="확인하고 예산에 반영"
            onPress={() => act(() => DailyRoll!.confirmTransaction(t.id, edit(), null))}
            disabled={parsedAmount == null}
            loading={busy}
          />
          <Button
            title={t.reviewStatus === 'DUPLICATE_CANDIDATE' ? '중복이에요(반영 안 함)' : '결제가 아니에요(무시)'}
            kind="secondary"
            onPress={() => act(() => DailyRoll!.ignoreTransaction(t.id))}
            loading={busy}
          />
        </>
      ) : (
        <>
          <Button title="수정 저장" onPress={() => act(() => DailyRoll!.updateTransaction(t.id, edit()), false)} loading={busy} disabled={parsedAmount == null} />
          {t.type === 'APPROVED' && t.reviewStatus === 'CONFIRMED' ? (
            <Button
              title={excluded ? '예산에 포함하기' : '예산에서 제외하기(고정지출 등)'}
              kind="secondary"
              onPress={() => act(() => DailyRoll!.setInclusion(t.id, excluded), false)}
              loading={busy}
            />
          ) : null}
          {t.reviewStatus !== 'IGNORED' ? (
            <Button title="이 거래 무시하기" kind="ghost" onPress={() => act(() => DailyRoll!.ignoreTransaction(t.id))} loading={busy} />
          ) : (
            <Button title="다시 확인하고 반영" kind="secondary" onPress={() => act(() => DailyRoll!.confirmTransaction(t.id, edit(), null))} loading={busy} />
          )}
        </>
      )}
      <Divider />
      <Body secondary style={{ fontSize: 12, marginTop: space.md }}>
        거래를 지우지 않고 &apos;무시&apos; 상태로 남겨 기록을 보존해요. 알림 원문은 저장하지 않아요.
      </Body>
    </ScrollView>
  );
}
