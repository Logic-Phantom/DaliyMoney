import { Tabs } from 'expo-router';
import { Text, type ColorValue } from 'react-native';

import { useColors } from '@/ui/theme';

/** 하단 탭 3개 유지(홈·리포트·설정). 아이콘 폰트 의존 없이 기호로 표시한다. */
function TabIcon({ symbol, color }: { symbol: string; color: ColorValue }) {
  return (
    <Text style={{ color, fontSize: 18 }} importantForAccessibility="no">
      {symbol}
    </Text>
  );
}

export default function TabsLayout() {
  const c = useColors();
  return (
    <Tabs
      screenOptions={{
        headerStyle: { backgroundColor: c.bg },
        headerTintColor: c.text,
        headerShadowVisible: false,
        tabBarStyle: { backgroundColor: c.surface, borderTopColor: c.border },
        tabBarActiveTintColor: c.text,
        tabBarInactiveTintColor: c.textSecondary,
        sceneStyle: { backgroundColor: c.bg },
      }}
    >
      <Tabs.Screen name="index" options={{ title: '홈', headerTitle: 'DailyRoll', tabBarIcon: ({ color }) => <TabIcon symbol="◎" color={color} /> }} />
      <Tabs.Screen name="report" options={{ title: '리포트', headerTitle: '월간 리포트', tabBarIcon: ({ color }) => <TabIcon symbol="▦" color={color} /> }} />
      <Tabs.Screen name="settings" options={{ title: '설정', headerTitle: '설정', tabBarIcon: ({ color }) => <TabIcon symbol="⚙" color={color} /> }} />
    </Tabs>
  );
}
