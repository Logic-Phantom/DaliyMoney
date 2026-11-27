import Constants from 'expo-constants';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, ScrollView, Switch, View } from 'react-native';

import { DailyRoll } from 'dailyroll-native';

import { collectionLabel, isCollectionHealthy, parseWonInput, relativeTime, seoulDateTime, won } from '@/lib/format';
import { errorMessage, useNative } from '@/lib/useDailyRoll';
import { Body, Button, Card, Divider, Field, ListRow, SectionTitle, StatusPill } from '@/ui/components';
import { space, useColors } from '@/ui/theme';

export default function SettingsScreen() {
  const c = useColors();
  const router = useRouter();
  const status = useNative(() => DailyRoll!.getStatus());
  const settings = useNative(() => DailyRoll!.getSettings());
  const [allowance, setAllowance] = useState('');
  const [rewardName, setRewardName] = useState('');
  const [rewardPrice, setRewardPrice] = useState('');
  const [syncedFrom, setSyncedFrom] = useState<typeof settings.data>(null);

  // 불러온 설정으로 입력창을 채운다(렌더 중 상태 조정 패턴: effect 연쇄 렌더 방지)
  if (settings.data && settings.data !== syncedFrom) {
    setSyncedFrom(settings.data);
    setAllowance(String(settings.data.dailyAllowance));
    setRewardName(settings.data.rewardName);
    setRewardPrice(String(settings.data.rewardUnitPrice));
  }

  const s = status.data;
  const st = settings.data;

  const saveAllowance = async () => {
    const v = parseWonInput(allowance, true);
    if (v == null) return Alert.alert('하루 예산', '원 단위 숫자로 입력해 주세요.');
    try {
      await DailyRoll!.setDailyAllowance(v);
      settings.reload();
      Alert.alert('저장했어요', `오늘부터 하루 ${won(v)}이 적용돼요. 지난 날짜의 누적 예산은 바뀌지 않아요.`);
    } catch (e) {
      Alert.alert('하루 예산', errorMessage(e));
    }
  };

  const saveReward = async () => {
    const p = parseWonInput(rewardPrice);
    if (p == null) return Alert.alert('보상 단가', '0보다 큰 원 단위 숫자로 입력해 주세요.');
    try {
      await DailyRoll!.setReward(rewardName, p);
      settings.reload();
    } catch (e) {
      Alert.alert('보상', errorMessage(e));
    }
  };

  const wipe = () =>
    Alert.alert('모든 데이터 삭제', '거래 기록·금고·예산 이력·설정이 모두 지워지고 되돌릴 수 없어요. 계속할까요?', [
      { text: '취소', style: 'cancel' },
      {
        text: '모두 삭제',
        style: 'destructive',
        onPress: async () => {
          await DailyRoll!.wipeAllData();
          router.replace('/onboarding');
        },
      },
    ]);

  return (
    <ScrollView contentContainerStyle={{ padding: space.lg, paddingBottom: space.xxl }}>
      <SectionTitle>알림 수집 상태</SectionTitle>
      <Card>
        {s ? (
          <>
            <StatusPill
              tone={isCollectionHealthy(s.collectionState) ? (s.reviewCount > 0 ? 'caution' : 'good') : 'over'}
              text={collectionLabel(s.collectionState, s.reviewCount)}
            />
            <Body secondary style={{ marginTop: space.sm, fontSize: 14 }}>
              마지막 결제 알림 감지: {s.lastPaymentDetectedAt ? `${seoulDateTime(s.lastPaymentDetectedAt)} (${relativeTime(s.lastPaymentDetectedAt)})` : '기록 없음'}
            </Body>
            {s.collectionState === 'NO_RECENT_DETECTION' ? (
              <Body secondary style={{ marginTop: space.xs, fontSize: 13 }}>
                한동안 감지 기록이 없어요. 결제가 없었던 것인지, 알림이 오지 않은 것인지는 앱이 알 수 없어요. 다음 결제 후 알림이
                기록되는지 확인해 주세요.
              </Body>
            ) : null}
            {!s.permissionGranted ? (
              <Button title="알림 접근 권한 설정" onPress={() => DailyRoll!.openNotificationListenerSettings()} />
            ) : s.collectionState === 'DISCONNECTED' ? (
              <Body secondary style={{ marginTop: space.sm, fontSize: 13 }}>
                권한은 있지만 시스템이 리스너 연결을 끊었어요. 휴대폰을 재시작하거나 알림 접근을 껐다 켜 보세요. 계속 끊기면 이 앱의 배터리
                사용을 &apos;제한 없음&apos;으로 바꾸는 것이 도움이 될 수 있어요(기기·One UI 버전마다 메뉴가 달라요).
              </Body>
            ) : null}
          </>
        ) : (
          <Body secondary>불러오는 중…</Body>
        )}
        <Divider />
        <ListRow title="파싱 테스트 · 시뮬레이터" subtitle="결제 알림 문구가 어떻게 읽히는지 확인해요(저장 안 함)" right="›" onPress={() => router.push('/tools/parser')} />
      </Card>

      <SectionTitle>예산</SectionTitle>
      <Card>
        <Field label="하루 기본 예산(원)" keyboardType="number-pad" value={allowance} onChangeText={setAllowance} />
        <Body secondary style={{ fontSize: 13 }}>바꾸면 오늘부터 적용돼요. 이번 달의 지난 날짜는 원래 예산으로 계산돼요.</Body>
        <Button title="하루 예산 저장" kind="secondary" onPress={saveAllowance} />
        <Divider />
        <View style={{ height: space.md }} />
        <Field label="보상 아이템 이름" value={rewardName} onChangeText={setRewardName} maxLength={20} />
        <Field label="보상 아이템 가격(원)" keyboardType="number-pad" value={rewardPrice} onChangeText={setRewardPrice} />
        <Button title="보상 저장" kind="secondary" onPress={saveReward} />
        <Divider />
        <ListRow title="고정지출 제외 키워드" subtitle="통신요금·구독 등은 하루 예산에서 빼요" right="›" onPress={() => router.push('/exclusions')} />
      </Card>

      <SectionTitle>위젯·개인정보</SectionTitle>
      <Card>
        <ToggleRow
          title="위젯 금액 숨기기"
          subtitle="홈 화면에 금액 대신 '열어서 확인'을 보여줘요"
          value={st?.hideWidgetAmounts ?? false}
          onChange={async (v) => {
            await DailyRoll!.setHideWidgetAmounts(v);
            settings.reload();
          }}
        />
        <Divider />
        <Body secondary style={{ fontSize: 13, marginTop: space.md }}>
          거래 정보는 이 기기 안에만 저장돼요. 서버 전송·회원가입·광고·분석 SDK가 없고, 배포용 빌드에는 인터넷 권한이 없어요. 클라우드
          백업·기기 이전에서도 제외돼요. 알림 원문은 저장하지 않아요. 저장소는 기기 기본 보호(앱 전용 영역)를 사용하며 별도 DB 암호화는
          적용하지 않았어요.
        </Body>
        <Button title="모든 거래 및 설정 데이터 삭제" kind="danger" onPress={wipe} />
      </Card>

      <SectionTitle>정기구독 알림</SectionTitle>
      <Card>
        <Body secondary style={{ fontSize: 14 }}>
          매월 결제일 3일 전 알림은 다음 버전에서 제공해요. 예정 금액은 실제 결제와 합산하지 않아요.
        </Body>
      </Card>

      <SectionTitle>개발자</SectionTitle>
      <Card>
        <ToggleRow
          title="개발자 모드"
          subtitle="가상 결제 시뮬레이터를 켜요. 가상 거래는 '가상'으로 표시돼요"
          value={st?.developerMode ?? false}
          onChange={async (v) => {
            await DailyRoll!.setDeveloperMode(v);
            settings.reload();
            status.reload();
          }}
        />
        <Divider />
        <Body secondary style={{ fontSize: 13, marginTop: space.md }}>
          DailyRoll {Constants.expoConfig?.version ?? ''} · Android SDK {s?.sdkInt ?? '-'}
          {s?.trackingStart ? ` · 수집 시작 ${s.trackingStart}` : ''}
        </Body>
      </Card>
      <View style={{ height: space.xl }} />
      <Body secondary style={{ fontSize: 12, color: c.textSecondary }}>
        특정 갤럭시 모델에서의 동작이 모든 Android 기기에서 같다고 보장하지 않아요. 은행 앱 알림 설정·절전 정책에 따라 결과가 달라질 수 있어요.
      </Body>
    </ScrollView>
  );
}

function ToggleRow({ title, subtitle, value, onChange }: { title: string; subtitle: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <View style={{ flex: 1 }}>
        <ListRow title={title} subtitle={subtitle} />
      </View>
      <Switch value={value} onValueChange={onChange} accessibilityLabel={title} />
    </View>
  );
}
