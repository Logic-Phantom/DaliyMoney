import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, ScrollView } from 'react-native';

import { DailyRoll } from 'dailyroll-native';

import { parseWonInput, requestKey } from '@/lib/format';
import { errorMessage } from '@/lib/useDailyRoll';
import { Body, Button, Field } from '@/ui/components';
import { space } from '@/ui/theme';

/** 수동 지출 입력(위젯 + 버튼의 목적지). 현금·다른 카드 결제 등 알림이 없는 지출용. */
export default function NewExpense() {
  const router = useRouter();
  const [amount, setAmount] = useState('');
  const [merchant, setMerchant] = useState('');
  const [busy, setBusy] = useState(false);
  const [key] = useState(requestKey); // 같은 화면에서 두 번 눌러도 한 번만 저장
  const value = parseWonInput(amount);

  const save = async () => {
    if (value == null) return;
    setBusy(true);
    try {
      await DailyRoll!.addManualExpense(value, merchant.trim() || null, Date.now(), key);
      router.back();
    } catch (e) {
      Alert.alert('저장하지 못했어요', errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg }} keyboardShouldPersistTaps="handled">
      <Field
        label="금액(원)"
        keyboardType="number-pad"
        value={amount}
        onChangeText={setAmount}
        autoFocus
        error={amount.length > 0 && value == null ? '1원 이상 정수로 입력해 주세요' : null}
      />
      <Field label="사용처(선택)" value={merchant} onChangeText={setMerchant} maxLength={40} placeholder="예: 편의점" />
      <Body secondary style={{ fontSize: 13 }}>
        직접 입력한 지출은 바로 예산에 반영돼요. 카드 결제 알림으로도 기록되면 두 번 빠질 수 있으니, 알림이 오지 않는 지출만 입력해 주세요.
      </Body>
      <Button title="저장" onPress={save} disabled={value == null} loading={busy} />
    </ScrollView>
  );
}
