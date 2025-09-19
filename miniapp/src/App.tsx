import { useEffect, useMemo, useState } from 'react';
import {
  ResolvedTelegramTheme,
  initializeTelegramWebApp,
  resolveTelegramTheme,
  subscribeOnThemeChange
} from './lib/tg';

const useTelegramTheme = (): [ResolvedTelegramTheme, () => void] => {
  const [theme, setTheme] = useState<ResolvedTelegramTheme>(() => resolveTelegramTheme());

  useEffect(() => {
    const webApp = initializeTelegramWebApp();
    setTheme(resolveTelegramTheme(webApp));
    const unsubscribe = subscribeOnThemeChange(setTheme);
    return () => {
      unsubscribe();
    };
  }, []);

  const refresh = () => {
    setTheme(resolveTelegramTheme());
  };

  return [theme, refresh];
};

const App = () => {
  const [theme, refreshTheme] = useTelegramTheme();

  useEffect(() => {
    const root = document.documentElement;
    root.style.setProperty('--tg-bg-color', theme.backgroundColor);
    root.style.setProperty('--tg-secondary-bg-color', theme.secondaryBackgroundColor);
    root.style.setProperty('--tg-text-color', theme.textColor);
    root.style.setProperty('--tg-hint-color', theme.hintColor);
    root.style.setProperty('--tg-link-color', theme.linkColor);
    root.style.setProperty('--tg-button-color', theme.buttonColor);
    root.style.setProperty('--tg-button-text-color', theme.buttonTextColor);

    document.body.style.backgroundColor = theme.backgroundColor;
    document.body.style.color = theme.textColor;
  }, [theme]);

  const gradientBackground = useMemo(
    () => `linear-gradient(145deg, ${theme.backgroundColor}, ${theme.secondaryBackgroundColor})`,
    [theme.backgroundColor, theme.secondaryBackgroundColor]
  );

  return (
    <div className="app" style={{ backgroundImage: gradientBackground }}>
      <main className="content">
        <header className="hero">
          <span className="tag">Mini App</span>
          <h1>Витрина кейсов</h1>
          <p className="description">
            Здесь скоро появятся подборки проектов и сценариев. Следите за обновлениями,
            чтобы узнать больше.
          </p>
        </header>

        <section className="actions">
          <button type="button" className="refresh-button" onClick={refreshTheme}>
            Обновить тему
          </button>
          <p className="hint">Нажмите, чтобы применить текущие параметры темы Telegram.</p>
        </section>
      </main>
    </div>
  );
};

export default App;
