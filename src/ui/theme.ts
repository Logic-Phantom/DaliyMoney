import { useColorScheme } from 'react-native';

/**
 * DailyRoll 디자인 토큰(초기 시안). 중립 배경 + 상태 포인트 1개.
 * 상태색은 포인트·보조 문구·게이지에만 쓰고 화면 배경은 바꾸지 않는다.
 * 라이트 모드 상태색은 흰 배경 위 작은 글자 대비(WCAG AA 4.5:1)를 위해 기획 시안보다 한 단계 어둡게 잡았다.
 */
export const palette = {
  light: {
    bg: '#F6F7F8',
    surface: '#FFFFFF',
    text: '#17191D',
    textSecondary: '#5F6570',
    border: '#E6E8EB',
    good: '#12896A',
    caution: '#A86F0E',
    over: '#D2434B',
    accent: '#17191D',
    onAccent: '#FFFFFF',
  },
  dark: {
    bg: '#15171B',
    surface: '#202329',
    text: '#F3F4F6',
    textSecondary: '#A5ABB5',
    border: '#343841',
    good: '#3CCB9F',
    caution: '#F0B04A',
    over: '#F27C82',
    accent: '#F3F4F6',
    onAccent: '#15171B',
  },
} as const;

export type Colors = { [K in keyof (typeof palette)['light']]: string };

export function useColors(): Colors {
  return useColorScheme() === 'dark' ? palette.dark : palette.light;
}

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const radius = { sm: 10, md: 16, lg: 20 } as const;

/** 금액은 고정폭 숫자 */
export const tabular = { fontVariant: ['tabular-nums' as const] };
