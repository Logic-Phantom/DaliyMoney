import { useState } from 'react';
import { Alert, ScrollView, Switch, View } from 'react-native';

import { DailyRoll } from 'dailyroll-native';

import { errorMessage, useNative } from '@/lib/useDailyRoll';
import { Body, Button, Card, Divider, Field, ListRow } from '@/ui/components';
import { space } from '@/ui/theme';

/**
 * 고정지출 제외 키워드 관리.
 * 규칙을 바꿔도 과거 거래는 자동으로 바뀌지 않는다. 소급 적용은 미리보기 후 사용자가 직접 실행한다.
 * 거래 화면에서 직접 지정한 포함/제외는 키워드보다 우선한다.
 */
export default function Exclusions() {
  const rules = useNative(() => DailyRoll!.listExclusionRules());
  const [keyword, setKeyword] = useState('');

  const add = async () => {
    try {
      await DailyRoll!.addExclusionRule(keyword);
      setKeyword('');
      rules.reload();
    } catch (e) {
      Alert.alert('키워드', errorMessage(e));
    }
  };

  const reapply = async () => {
    const preview = await DailyRoll!.previewExclusionReapply();
    if (preview.length === 0) return Alert.alert('소급 적용', '바뀌는 과거 거래가 없어요.');
    const lines = preview
      .slice(0, 8)
      .map((p) => `· ${p.merchantName ?? '가맹점 미확인'} → ${p.nextInclusion === 'AUTO_EXCLUDED' ? '제외' : '포함'}`)
      .join('\n');
    Alert.alert('과거 거래에 적용할까요?', `${preview.length}건이 바뀌어요(직접 지정한 거래는 제외).\n${lines}${preview.length > 8 ? '\n…' : ''}`, [
      { text: '취소', style: 'cancel' },
      {
        text: '적용',
        onPress: async () => {
          const n = await DailyRoll!.applyExclusionReapply();
          Alert.alert('적용했어요', `${n}건을 다시 계산했어요.`);
        },
      },
    ]);
  };

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg }} keyboardShouldPersistTaps="handled">
      <Body secondary style={{ fontSize: 13, marginBottom: space.md }}>
        가맹점명에 키워드가 들어 있는 승인 거래는 하루 예산에서 자동으로 빠지고 타임라인에 &apos;예산 제외&apos;로 표시돼요. 짧은 영문
        키워드(KT 등)는 단어 단위로만 찾아요.
      </Body>
      <Card>
        {(rules.data ?? []).map((r, i) => (
          <View key={r.id}>
            {i > 0 ? <Divider /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <View style={{ flex: 1 }}>
                <ListRow
                  title={r.keyword}
                  subtitle={r.enabled ? '사용 중' : '꺼짐'}
                  onPress={() =>
                    Alert.alert('키워드 삭제', `'${r.keyword}' 를 삭제할까요?`, [
                      { text: '취소', style: 'cancel' },
                      { text: '삭제', style: 'destructive', onPress: () => DailyRoll!.deleteExclusionRule(r.id).then(rules.reload) },
                    ])
                  }
                />
              </View>
              <Switch
                value={r.enabled}
                accessibilityLabel={`${r.keyword} 사용`}
                onValueChange={(v) => DailyRoll!.setExclusionRuleEnabled(r.id, v).then(rules.reload)}
              />
            </View>
          </View>
        ))}
      </Card>
      <Field label="키워드 추가" value={keyword} onChangeText={setKeyword} maxLength={30} placeholder="예: 헬스장" />
      <Button title="추가" kind="secondary" onPress={add} disabled={keyword.trim().length === 0} />
      <Button title="과거 거래에 소급 적용(미리보기)" kind="ghost" onPress={reapply} />
    </ScrollView>
  );
}
