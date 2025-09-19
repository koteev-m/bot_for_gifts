export type TelegramColorScheme = 'light' | 'dark';

export interface TelegramThemeParams {
  accent_color?: string;
  bg_color?: string;
  text_color?: string;
  hint_color?: string;
  link_color?: string;
  button_color?: string;
  button_text_color?: string;
  secondary_bg_color?: string;
}

export interface TelegramWebApp {
  readonly colorScheme: TelegramColorScheme;
  readonly themeParams: TelegramThemeParams;
  ready(): void;
  expand(): void;
  isExpanded: boolean;
  onEvent(eventType: 'themeChanged', eventHandler: () => void): void;
  offEvent(eventType: 'themeChanged', eventHandler: () => void): void;
}

declare global {
  interface Window {
    Telegram?: {
      WebApp?: TelegramWebApp;
    };
  }
}

let cachedWebApp: TelegramWebApp | undefined;
let initialized = false;

export const getTelegramWebApp = (): TelegramWebApp | undefined => {
  if (typeof window === 'undefined') {
    return undefined;
  }

  if (cachedWebApp) {
    return cachedWebApp;
  }

  cachedWebApp = window.Telegram?.WebApp;
  return cachedWebApp;
};

const LIGHT_FALLBACK = {
  backgroundColor: '#ffffff',
  secondaryBackgroundColor: '#f5f5f5',
  textColor: '#0a0a0a',
  hintColor: '#6b6b6b',
  linkColor: '#3390ec',
  buttonColor: '#3390ec',
  buttonTextColor: '#ffffff'
} as const;

const DARK_FALLBACK = {
  backgroundColor: '#0f172a',
  secondaryBackgroundColor: '#1e293b',
  textColor: '#f8fafc',
  hintColor: '#94a3b8',
  linkColor: '#5ea2ff',
  buttonColor: '#5ea2ff',
  buttonTextColor: '#0b1120'
} as const;

export interface ResolvedTelegramTheme {
  backgroundColor: string;
  secondaryBackgroundColor: string;
  textColor: string;
  hintColor: string;
  linkColor: string;
  buttonColor: string;
  buttonTextColor: string;
}

const resolveColor = (value: string | undefined, fallback: string): string =>
  value && /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(value) ? value : fallback;

export const resolveTelegramTheme = (
  webApp: TelegramWebApp | undefined = getTelegramWebApp()
): ResolvedTelegramTheme => {
  const scheme = webApp?.colorScheme ?? 'light';
  const base = scheme === 'dark' ? DARK_FALLBACK : LIGHT_FALLBACK;
  const params = webApp?.themeParams;

  return {
    backgroundColor: resolveColor(params?.bg_color, base.backgroundColor),
    secondaryBackgroundColor: resolveColor(
      params?.secondary_bg_color,
      base.secondaryBackgroundColor
    ),
    textColor: resolveColor(params?.text_color, base.textColor),
    hintColor: resolveColor(params?.hint_color, base.hintColor),
    linkColor: resolveColor(params?.link_color, base.linkColor),
    buttonColor: resolveColor(params?.button_color, base.buttonColor),
    buttonTextColor: resolveColor(params?.button_text_color, base.buttonTextColor)
  };
};

export const initializeTelegramWebApp = (): TelegramWebApp | undefined => {
  const webApp = getTelegramWebApp();
  if (!webApp || initialized) {
    return webApp;
  }

  if (typeof webApp.ready === 'function') {
    webApp.ready();
  }

  if (!webApp.isExpanded) {
    try {
      webApp.expand();
    } catch (error) {
      console.warn('Failed to expand Telegram WebApp viewport:', error);
    }
  }

  initialized = true;
  return webApp;
};

export const subscribeOnThemeChange = (
  listener: (theme: ResolvedTelegramTheme) => void
): (() => void) => {
  const webApp = initializeTelegramWebApp();

  if (!webApp) {
    return () => undefined;
  }

  const handler = () => listener(resolveTelegramTheme(webApp));
  webApp.onEvent('themeChanged', handler);
  return () => webApp.offEvent('themeChanged', handler);
};
