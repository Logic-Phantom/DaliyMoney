import type { ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from 'react-native';

import { radius, space, tabular, useColors } from './theme';

export function Screen({ children }: { children: ReactNode }) {
  const c = useColors();
  return <View style={{ flex: 1, backgroundColor: c.bg }}>{children}</View>;
}

/** 표면 카드. 테두리·그림자·배경을 모두 주지 않고 표면색 하나로만 위계를 만든다. */
export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const c = useColors();
  return <View style={[styles.card, { backgroundColor: c.surface }, style]}>{children}</View>;
}

export function SectionTitle({ children, right }: { children: ReactNode; right?: ReactNode }) {
  const c = useColors();
  return (
    <View style={styles.sectionRow}>
      <Text accessibilityRole="header" style={[styles.sectionTitle, { color: c.text }]}>
        {children}
      </Text>
      {right}
    </View>
  );
}

export function Body({ children, secondary, style }: { children: ReactNode; secondary?: boolean; style?: StyleProp<TextStyle> }) {
  const c = useColors();
  return <Text style={[styles.body, { color: secondary ? c.textSecondary : c.text }, style]}>{children}</Text>;
}

export function Amount({ children, size = 34, color, style }: { children: ReactNode; size?: number; color?: string; style?: StyleProp<TextStyle> }) {
  const c = useColors();
  return (
    <Text
      style={[{ fontSize: size, fontWeight: '700', color: color ?? c.text, letterSpacing: -0.5 }, tabular, style]}
      adjustsFontSizeToFit
      numberOfLines={1}
    >
      {children}
    </Text>
  );
}

export function Metric({ label, value }: { label: string; value: string }) {
  const c = useColors();
  return (
    <View style={styles.metric} accessible accessibilityLabel={`${label} ${value}`}>
      <Text style={[styles.metricLabel, { color: c.textSecondary }]}>{label}</Text>
      <Text style={[styles.metricValue, { color: c.text }, tabular]} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
    </View>
  );
}

type ButtonKind = 'primary' | 'secondary' | 'danger' | 'ghost';

export function Button({
  title,
  onPress,
  kind = 'primary',
  disabled,
  loading,
  accessibilityHint,
}: {
  title: string;
  onPress: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
  loading?: boolean;
  accessibilityHint?: string;
}) {
  const c = useColors();
  const bg = kind === 'primary' ? c.accent : kind === 'danger' ? c.over : kind === 'secondary' ? c.border : 'transparent';
  const fg = kind === 'primary' ? c.onAccent : kind === 'danger' ? '#FFFFFF' : c.text;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled || !!loading }}
      accessibilityHint={accessibilityHint}
      disabled={disabled || loading}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, opacity: disabled ? 0.4 : pressed ? 0.75 : 1 },
      ]}
    >
      {loading ? <ActivityIndicator color={fg} /> : <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

export function Field({ label, error, ...props }: TextInputProps & { label: string; error?: string | null }) {
  const c = useColors();
  return (
    <View style={{ marginBottom: space.md }}>
      <Text style={[styles.metricLabel, { color: c.textSecondary, marginBottom: space.xs }]}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={c.textSecondary}
        style={[styles.input, { color: c.text, backgroundColor: c.surface, borderColor: error ? c.over : c.border }]}
        {...props}
      />
      {error ? <Text style={[styles.metricLabel, { color: c.over, marginTop: space.xs }]}>{error}</Text> : null}
    </View>
  );
}

/** 색만으로 상태를 전달하지 않도록 기호 + 텍스트를 함께 쓴다. */
export function StatusPill({ tone, text }: { tone: 'good' | 'caution' | 'over' | 'neutral'; text: string }) {
  const c = useColors();
  const color = tone === 'good' ? c.good : tone === 'caution' ? c.caution : tone === 'over' ? c.over : c.textSecondary;
  const symbol = tone === 'good' ? '●' : tone === 'caution' ? '▲' : tone === 'over' ? '■' : '○';
  return (
    <View style={styles.pill} accessible accessibilityLabel={text}>
      <Text style={{ color, fontSize: 10, marginRight: 6 }} importantForAccessibility="no">
        {symbol}
      </Text>
      <Text style={[styles.pillText, { color }]}>{text}</Text>
    </View>
  );
}

export function ListRow({
  title,
  subtitle,
  right,
  rightColor,
  onPress,
  accessibilityLabel,
}: {
  title: string;
  subtitle?: string;
  right?: string;
  rightColor?: string;
  onPress?: () => void;
  accessibilityLabel?: string;
}) {
  const c = useColors();
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={accessibilityLabel}
      style={({ pressed }) => [styles.row, { opacity: pressed ? 0.6 : 1 }]}
    >
      <View style={{ flex: 1, paddingRight: space.md }}>
        <Text style={[styles.rowTitle, { color: c.text }]} numberOfLines={1}>
          {title}
        </Text>
        {subtitle ? (
          <Text style={[styles.rowSub, { color: c.textSecondary }]} numberOfLines={2}>
            {subtitle}
          </Text>
        ) : null}
      </View>
      {right ? <Text style={[styles.rowRight, { color: rightColor ?? c.text }, tabular]}>{right}</Text> : null}
    </Pressable>
  );
}

export function Divider() {
  const c = useColors();
  return <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: c.border }} />;
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.lg, padding: space.lg, marginBottom: space.md },
  sectionRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: space.lg, marginBottom: space.sm },
  sectionTitle: { fontSize: 17, fontWeight: '700' },
  body: { fontSize: 15, lineHeight: 22 },
  metric: { flex: 1, minWidth: 90 },
  metricLabel: { fontSize: 13 },
  metricValue: { fontSize: 16, fontWeight: '600', marginTop: 2 },
  button: { minHeight: 52, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', paddingHorizontal: space.lg, marginTop: space.sm },
  buttonText: { fontSize: 16, fontWeight: '600' },
  input: { minHeight: 52, borderRadius: radius.sm, borderWidth: 1, paddingHorizontal: space.md, fontSize: 17 },
  pill: { flexDirection: 'row', alignItems: 'center' },
  pillText: { fontSize: 14, fontWeight: '600' },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 56, paddingVertical: space.sm },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowSub: { fontSize: 13, marginTop: 2 },
  rowRight: { fontSize: 15, fontWeight: '600' },
});
