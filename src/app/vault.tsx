import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, ScrollView, View } from 'react-native';

import { DailyRoll } from 'dailyroll-native';

import { parseWonInput, requestKey, won } from '@/lib/format';
import { errorMessage, useNative } from '@/lib/useDailyRoll';
import { Amount, Body, Button, Card, Field, Metric } from '@/ui/components';
import { space } from '@/ui/theme';

/**
 * 금고: 사용 가능 예산 → 앱 안의 가상 저축 기록으로 옮기는 내부 이동(소비 아님).
 * 실제 은행 계좌로 돈이 이동하지 않는다. 잠금 해제는 기존 기록을 지우지 않고 반대 이벤트로 남긴다.
 */
export default function Vault() {
  const router = useRouter();
  const { data } = useNative(() => DailyRoll!.getDashboard());
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [depositKey] = useState(requestKey);
  const [withdrawKey] = useState(requestKey);
  const value = parseWonInput(amount);
  const s = data?.snapshot;
  const maxDeposit = Math.max(0, s?.available ?? 0);

  const run = async (kind: 'deposit' | 'withdraw') => {
    if (value == null) return;
    setBusy(true);
    try {
      if (kind === 'deposit') await DailyRoll!.vaultDeposit(value, depositKey);
      else await DailyRoll!.vaultWithdraw(value, withdrawKey);
      router.back();
    } catch (e) {
      Alert.alert('금고', errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg }} keyboardShouldPersistTaps="handled">
      <Card>
        <Body secondary>금고 잔액</Body>
        <Amount size={30}>{won(s?.vaultBalance ?? 0)}</Amount>
        <View style={{ flexDirection: 'row', gap: space.md, marginTop: space.md }}>
          <Metric label="이번 달 잠금" value={won(s?.monthVaultDeposit ?? 0)} />
          <Metric label="이번 달 해제" value={won(s?.monthVaultWithdrawal ?? 0)} />
          <Metric label="잠글 수 있는 금액" value={won(maxDeposit)} />
        </View>
      </Card>
      <Field
        label="금액(원)"
        keyboardType="number-pad"
        value={amount}
        onChangeText={setAmount}
        error={amount.length > 0 && value == null ? '1원 이상 정수로 입력해 주세요' : null}
      />
      <Body secondary style={{ fontSize: 13 }}>
        금고는 앱 안에서만 관리하는 가상 저축 기록이에요. 실제 은행 계좌로 돈이 옮겨지지 않아요. 잠근 금액은 지금 쓸 수 있는 금액에서
        빠지고, 해제하면 다시 돌아와요. 소비 통계에는 포함되지 않아요.
      </Body>
      <Button title="금고에 잠그기" onPress={() => run('deposit')} disabled={value == null || value > maxDeposit} loading={busy} />
      <Button
        title="잠금 해제(예산으로 돌려놓기)"
        kind="secondary"
        onPress={() => run('withdraw')}
        disabled={value == null || value > (s?.vaultBalance ?? 0)}
        loading={busy}
      />
    </ScrollView>
  );
}
