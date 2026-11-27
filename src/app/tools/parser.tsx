import { useState } from 'react';
import { Alert, Pressable, ScrollView, Text, View } from 'react-native';

import { DailyRoll, type ParseTestResult, type SimulationKind } from 'dailyroll-native';

import { REASON_LABEL, TYPE_LABEL, parseWonInput, seoulDateTime, won } from '@/lib/format';
import { errorMessage, useNative } from '@/lib/useDailyRoll';
import { Body, Button, Card, Field, ListRow, SectionTitle } from '@/ui/components';
import { radius, space, useColors } from '@/ui/theme';

const SOURCES = [
  { pkg: 'com.kakaobank.channel', label: '카카오뱅크' },
  { pkg: 'com.kakao.talk', label: '카카오톡' },
  { pkg: 'com.samsung.android.spay', label: '삼성월렛' },
];

const SIMS: { kind: SimulationKind; label: string }[] = [
  { kind: 'APPROVE', label: '승인' },
  { kind: 'CANCEL_FULL', label: '전체 취소' },
  { kind: 'CANCEL_PARTIAL', label: '부분 취소' },
  { kind: 'SAME_AMOUNT_TWICE', label: '같은 금액 연속 결제' },
  { kind: 'FIXED_COST', label: '고정지출(넷플릭스)' },
];

/**
 * 진단 도구.
 * - 파싱 테스트: 붙여 넣은 알림 문구를 파서로 해석만 하고 저장하지 않는다.
 * - 시뮬레이터(개발자 모드): 실제 알림 경로와 분리된 가상 거래를 넣는다. 데이터에 '가상'으로 구분된다.
 */
export default function ParserTool() {
  const c = useColors();
  const status = useNative(() => DailyRoll!.getStatus());
  const [pkg, setPkg] = useState(SOURCES[0].pkg);
  const [title, setTitle] = useState('');
  const [text, setText] = useState('[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점 / 11/27 12:30');
  const [result, setResult] = useState<ParseTestResult | null>(null);
  const [simAmount, setSimAmount] = useState('4500');
  const [simMerchant, setSimMerchant] = useState('메가커피');

  const parse = async () => {
    try {
      setResult(await DailyRoll!.testParse(pkg, title || null, text));
    } catch (e) {
      Alert.alert('파싱 테스트', errorMessage(e));
    }
  };

  const simulate = async (kind: SimulationKind) => {
    const amount = parseWonInput(simAmount);
    if (amount == null) return Alert.alert('시뮬레이터', '금액을 확인해 주세요');
    try {
      const outcomes = await DailyRoll!.simulate(kind, amount, simMerchant || '테스트 가맹점');
      Alert.alert('가상 거래 추가', outcomes.join(', '));
    } catch (e) {
      Alert.alert('시뮬레이터', errorMessage(e));
    }
  };

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg, paddingBottom: space.xxl }} keyboardShouldPersistTaps="handled">
      <SectionTitle>파싱 테스트</SectionTitle>
      <Body secondary style={{ fontSize: 13, marginBottom: space.md }}>
        실제 받은 결제 알림 문구를 붙여 넣어 어떻게 인식되는지 확인해요. 입력한 문구는 저장하지 않아요. 이름·카드번호는 지우고 넣어 주세요.
      </Body>
      <View style={{ flexDirection: 'row', gap: space.sm, marginBottom: space.md }}>
        {SOURCES.map((s) => (
          <Pressable
            key={s.pkg}
            accessibilityRole="radio"
            accessibilityState={{ selected: pkg === s.pkg }}
            onPress={() => setPkg(s.pkg)}
            style={{
              flex: 1,
              minHeight: 44,
              borderRadius: radius.sm,
              borderWidth: 1,
              borderColor: pkg === s.pkg ? c.text : c.border,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Text style={{ color: c.text, fontWeight: pkg === s.pkg ? '700' : '400' }}>{s.label}</Text>
          </Pressable>
        ))}
      </View>
      <Field label="알림 제목(선택)" value={title} onChangeText={setTitle} />
      <Field label="알림 본문" value={text} onChangeText={setText} multiline style={{ minHeight: 96, textAlignVertical: 'top' }} />
      <Button title="파싱해 보기" onPress={parse} />

      {result ? (
        <Card style={{ marginTop: space.md }}>
          <ListRow title="결과" right={{ PAYMENT: '결제', IGNORED: '무시(결제 아님)', NEEDS_REVIEW: '확인 필요' }[result.result]} />
          <ListRow title="금액" right={result.amount == null ? '-' : won(result.amount)} />
          <ListRow title="유형" right={result.transactionType ? TYPE_LABEL[result.transactionType] : '-'} />
          <ListRow title="가맹점" right={result.merchantName ?? '-'} />
          <ListRow title="시각" right={result.occurredAt ? seoulDateTime(result.occurredAt) : '-'} />
          <ListRow title="신뢰도" right={result.confidence} />
          <ListRow title="자동 반영" right={result.autoApplicable ? '예' : '아니요'} />
          {result.reasons.map((r) => (
            <Body key={r} secondary style={{ fontSize: 13 }}>
              · {REASON_LABEL[r]}
            </Body>
          ))}
        </Card>
      ) : null}

      <SectionTitle>시뮬레이터</SectionTitle>
      {status.data?.developerMode ? (
        <>
          <Field label="금액(원)" keyboardType="number-pad" value={simAmount} onChangeText={setSimAmount} />
          <Field label="가맹점" value={simMerchant} onChangeText={setSimMerchant} />
          {SIMS.map((s) => (
            <Button key={s.kind} title={s.label} kind="secondary" onPress={() => simulate(s.kind)} />
          ))}
          <Body secondary style={{ fontSize: 12, marginTop: space.sm }}>
            전체 취소는 같은 가맹점·같은 금액의 가상 승인과 연결돼요. 가상 거래는 실제 거래와 구분해 저장돼요.
          </Body>
        </>
      ) : (
        <Body secondary style={{ fontSize: 13 }}>설정 &gt; 개발자 모드를 켜면 가상 결제를 넣어 볼 수 있어요.</Body>
      )}
    </ScrollView>
  );
}
