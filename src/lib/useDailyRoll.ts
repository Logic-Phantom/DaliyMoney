import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { DailyRoll } from 'dailyroll-native';

export interface Loadable<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  reload: () => void;
}

/**
 * 네이티브 조회를 화면에 연결한다.
 * - 화면 포커스, 앱 복귀(AppState active), 원장 변경 이벤트(onChange), deps 변경 시 다시 읽는다.
 * - 앱 복귀 시 위젯도 함께 새로 그린다(자정이 지난 뒤 복귀하는 경우 등).
 */
export function useNative<T>(load: () => Promise<T>, deps: readonly unknown[] = []): Loadable<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(load);

  useEffect(() => {
    loadRef.current = load;
  });

  const reload = useCallback(async () => {
    try {
      const d = await loadRef.current();
      setData(d);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const depsKey = JSON.stringify(deps);
  useEffect(() => {
    void reload();
  }, [reload, depsKey]);

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') {
        DailyRoll?.refreshWidgets().catch(() => undefined);
        void reload();
      }
    });
    const change = DailyRoll?.addListener('onChange', () => void reload());
    return () => {
      sub.remove();
      change?.remove();
    };
  }, [reload]);

  return { data, error, loading, reload: () => void reload() };
}

/** 사용자에게 보여줄 오류 문구 */
export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return '처리하지 못했어요. 다시 시도해 주세요.';
}
