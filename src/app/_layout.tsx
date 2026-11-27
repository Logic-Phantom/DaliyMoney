import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { DailyRoll } from 'dailyroll-native';

import { useColors } from '@/ui/theme';

export default function RootLayout() {
  const c = useColors();
  const router = useRouter();
  const segments = useSegments();
  const [consented, setConsented] = useState<boolean | null>(null);

  useEffect(() => {
    if (!DailyRoll) return;
    DailyRoll.getStatus()
      .then((s) => setConsented(s.consented))
      .catch(() => setConsented(false));
  }, [segments]);

  useEffect(() => {
    if (consented === false && segments[0] !== 'onboarding') router.replace('/onboarding');
  }, [consented, segments, router]);

  if (!DailyRoll) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: c.bg }}>
        <Text style={{ color: c.text, fontSize: 17, fontWeight: '700', marginBottom: 8 }}>네이티브 모듈이 없어요</Text>
        <Text style={{ color: c.textSecondary, textAlign: 'center', lineHeight: 22 }}>
          DailyRoll 은 결제 알림을 읽는 Android 네이티브 기능이 필요해 Expo Go·웹에서는 동작하지 않아요. EAS Build 또는
          개발 빌드(npm run android)로 설치해 주세요.
        </Text>
      </View>
    );
  }

  return (
    <SafeAreaProvider>
      <StatusBar style="auto" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: c.bg },
          headerTintColor: c.text,
          headerShadowVisible: false,
          contentStyle: { backgroundColor: c.bg },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="onboarding" options={{ headerShown: false, gestureEnabled: false }} />
        <Stack.Screen name="expense/new" options={{ title: '지출 직접 입력', presentation: 'modal' }} />
        <Stack.Screen name="vault" options={{ title: '금고', presentation: 'modal' }} />
        <Stack.Screen name="transaction/[id]" options={{ title: '거래 확인' }} />
        <Stack.Screen name="tools/parser" options={{ title: '파싱 테스트 · 시뮬레이터' }} />
        <Stack.Screen name="exclusions" options={{ title: '고정지출 제외 키워드' }} />
      </Stack>
    </SafeAreaProvider>
  );
}
