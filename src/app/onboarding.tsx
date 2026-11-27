import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Alert, AppState, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { DailyRoll, type Status } from 'dailyroll-native';

import { errorMessage } from '@/lib/useDailyRoll';
import { Body, Button, Card } from '@/ui/components';
import { space, useColors } from '@/ui/theme';

/**
 * 알림 접근 목적·처리 항목·저장 위치·외부 전송 여부를 먼저 고지하고,
 * 사용자가 버튼을 눌러 명시적으로 동의한 뒤에만 OS 설정 화면으로 이동한다.
 */
export default function Onboarding() {
  const c = useColors();
  const router = useRouter();
  const [status, setStatus] = useState<Status | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = () => DailyRoll?.getStatus().then(setStatus).catch(() => undefined);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (s) => s === 'active' && refresh());
    return () => sub.remove();
  }, []);

  const agreeAndOpen = async () => {
    if (!DailyRoll) return;
    setBusy(true);
    try {
      setStatus(await DailyRoll.giveConsent());
      const opened = await DailyRoll.openNotificationListenerSettings();
      if (!opened) Alert.alert('설정을 열 수 없어요', '휴대폰 설정 > 알림 > 기기 및 앱 알림(알림 읽기) 에서 DailyRoll 을 허용해 주세요.');
    } catch (e) {
      Alert.alert('오류', errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const granted = status?.permissionGranted ?? false;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: c.bg }}>
      <ScrollView contentContainerStyle={{ padding: space.xl }}>
        <Text accessibilityRole="header" style={{ color: c.text, fontSize: 28, fontWeight: '800', marginTop: space.xl }}>
          DailyRoll
        </Text>
        <Body secondary style={{ marginTop: space.sm, marginBottom: space.xl }}>
          하루 예산을 정하고, 남은 금액을 이번 달 안에서 다음 날로 이월해 보여줘요.
        </Body>

        <Card>
          <Section title="왜 알림을 읽나요?">
            카카오뱅크 체크카드 결제 알림을 읽어 지출을 자동으로 기록하기 위해서예요. 은행 로그인이나 금융사 연동은 하지 않아요.
          </Section>
          <Section title="읽는 정보">결제 금액, 승인·취소 여부, 가맹점명, 결제 시각, 알림 출처(앱 이름)</Section>
          <Section title="처리 방식">
            Android 알림 접근 권한은 기술적으로 다른 앱 알림에도 접근할 수 있는 권한이에요. DailyRoll 은 카카오뱅크·카카오톡(카카오뱅크
            알림톡)·삼성월렛 알림만 기기 안에서 분석하고, 나머지 알림은 읽지 않고 바로 버려요. 알림 원문은 저장하지 않아요.
          </Section>
          <Section title="저장 위치·외부 전송">
            추출한 거래 정보는 이 휴대폰 안에만 저장돼요. 서버·회원가입·광고·분석 SDK가 없고, 배포용 앱에는 인터넷 권한 자체가 없어요.
            클라우드 백업과 기기 이전에서도 제외돼요.
          </Section>
          <Section title="한계" last>
            알림이 오지 않은 결제(은행 앱 알림 꺼짐, 절전으로 지연 등)는 기록할 수 없고, 권한을 켜기 전의 과거 거래도 복구할 수 없어요.
            확실하지 않은 거래는 자동 반영하지 않고 &apos;확인 필요&apos;로 보여 드려요.
          </Section>
        </Card>

        {granted ? (
          <View>
            <Body style={{ color: c.good, fontWeight: '600', marginTop: space.md }}>알림 접근이 허용되었어요.</Body>
            <Button title="시작하기" onPress={() => router.replace('/')} />
          </View>
        ) : (
          <View>
            <Button title="동의하고 알림 권한 설정" onPress={agreeAndOpen} loading={busy} accessibilityHint="알림 접근 설정 화면으로 이동합니다" />
            {status?.consented ? (
              <>
                <Body secondary style={{ marginTop: space.md }}>
                  설정에서 DailyRoll 을 허용한 뒤 돌아오면 자동으로 확인해요. APK 로 직접 설치했다면 &apos;제한된 설정&apos;을 먼저 허용해야 할 수 있어요
                  (앱 정보 화면 오른쪽 위 메뉴 · 기기·OS 버전에 따라 위치가 달라요).
                </Body>
                <Button title="앱 정보 열기(제한된 설정 허용)" kind="secondary" onPress={() => DailyRoll?.openAppDetailsSettings()} />
                <Button title="권한 없이 둘러보기(수동 입력만)" kind="ghost" onPress={() => router.replace('/')} />
              </>
            ) : null}
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function Section({ title, children, last }: { title: string; children: React.ReactNode; last?: boolean }) {
  const c = useColors();
  return (
    <View style={{ marginBottom: last ? 0 : space.lg }}>
      <Text style={{ color: c.text, fontSize: 15, fontWeight: '700', marginBottom: 4 }}>{title}</Text>
      <Body secondary>{children}</Body>
    </View>
  );
}
