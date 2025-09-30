import {
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react';
import { openInvoice } from './lib/pay';
import {
  ResolvedTelegramTheme,
  initializeTelegramWebApp,
  resolveTelegramTheme,
  subscribeOnThemeChange
} from './lib/tg';

interface MiniCase {
  id: string;
  title: string;
  priceStars: number;
  thumbnail: string;
  shortDescription: string;
}

interface MiniCasesState {
  cases: MiniCase[];
  loading: boolean;
  error: string | null;
  initDataMissing: boolean;
}

interface PaymentState {
  caseId: string;
  caseTitle: string;
  status: 'loading' | 'paid' | 'cancelled' | 'failed' | 'error';
  message: string;
}

interface InvoiceResponsePayload {
  invoiceLink: string;
  payload: string;
}

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

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const normalizePriceStars = (value: unknown): number | null => {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.round(value);
  }

  if (typeof value === 'string') {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }
  }

  return null;
};

const parseMiniCases = (data: unknown): MiniCase[] => {
  if (!Array.isArray(data)) {
    return [];
  }

  return data.flatMap((item) => {
    if (!isRecord(item)) {
      return [];
    }

    const { id, title, priceStars, thumbnail, shortDescription } = item;
    const normalizedPrice = normalizePriceStars(priceStars);

    if (
      typeof id === 'string' &&
      id.trim().length > 0 &&
      typeof title === 'string' &&
      title.trim().length > 0 &&
      typeof thumbnail === 'string' &&
      thumbnail.trim().length > 0 &&
      typeof shortDescription === 'string' &&
      shortDescription.trim().length > 0 &&
      normalizedPrice !== null &&
      normalizedPrice > 0
    ) {
      return [
        {
          id,
          title,
          priceStars: normalizedPrice,
          thumbnail,
          shortDescription
        }
      ];
    }

    return [];
  });
};

const parseInvoiceResponse = (data: unknown): InvoiceResponsePayload | null => {
  if (!isRecord(data)) {
    return null;
  }

  const { invoiceLink, payload } = data;
  if (typeof invoiceLink !== 'string' || invoiceLink.trim().length === 0) {
    return null;
  }

  if (typeof payload !== 'string' || payload.trim().length === 0) {
    return null;
  }

  return { invoiceLink: invoiceLink.trim(), payload: payload.trim() };
};

const useMiniCases = (): MiniCasesState => {
  const [state, setState] = useState<MiniCasesState>({
    cases: [],
    loading: true,
    error: null,
    initDataMissing: false
  });

  useEffect(() => {
    let disposed = false;
    const controller = new AbortController();

    const loadCases = async () => {
      const webApp = initializeTelegramWebApp();
      const initData = webApp?.initData ?? '';

      if (!initData) {
        if (!disposed) {
          setState({ cases: [], loading: false, error: null, initDataMissing: true });
        }
        return;
      }

      setState((prev) => ({
        ...prev,
        loading: true,
        error: null,
        initDataMissing: false
      }));

      try {
        const response = await fetch(
          `/api/miniapp/cases?initData=${encodeURIComponent(initData)}`,
          {
            headers: { Accept: 'application/json' },
            signal: controller.signal
          }
        );

        if (!response.ok) {
          const fallback = await response.text().catch(() => '');
          throw new Error(
            fallback || `Не удалось загрузить витрину (код ${response.status})`
          );
        }

        const raw: unknown = await response.json();
        if (!disposed) {
          setState({
            cases: parseMiniCases(raw),
            loading: false,
            error: null,
            initDataMissing: false
          });
        }
      } catch (error) {
        if (controller.signal.aborted || disposed) {
          return;
        }

        const message =
          error instanceof Error
            ? error.message
            : 'Не удалось загрузить витрину кейсов';
        setState((prev) => ({
          ...prev,
          loading: false,
          error: message,
          initDataMissing: false
        }));
      }
    };

    void loadCases();

    return () => {
      disposed = true;
      controller.abort();
    };
  }, []);

  return state;
};

const renderCasesContent = (
  cases: MiniCase[],
  loading: boolean,
  error: string | null,
  initDataMissing: boolean,
  onBuy: (miniCase: MiniCase) => void,
  purchasingCaseId: string | null
): ReactNode => {
  if (initDataMissing) {
    return (
      <p className="cases-message cases-message--hint">
        Mini App не передал initData. Откройте витрину из Telegram, чтобы увидеть кейсы.
      </p>
    );
  }

  if (loading) {
    return <p className="cases-message">Загружаем подборки кейсов…</p>;
  }

  if (error) {
    return <p className="cases-message cases-message--error">{error}</p>;
  }

  if (cases.length === 0) {
    return (
      <p className="cases-message cases-message--hint">
        Кейсы скоро появятся. Мы обновим витрину автоматически.
      </p>
    );
  }

  return (
    <div className="cases-grid">
      {cases.map((miniCase) => {
        const isProcessing = purchasingCaseId === miniCase.id;
        return (
          <article key={miniCase.id} className="case-card">
            <div className="case-thumbnail">
              <img
                src={miniCase.thumbnail}
                alt={`Обложка кейса «${miniCase.title}»`}
                loading="lazy"
                decoding="async"
              />
            </div>
            <div className="case-body">
              <h3 className="case-title">{miniCase.title}</h3>
              <p className="case-description">{miniCase.shortDescription}</p>
            </div>
            <div className="case-footer">
              <div className="case-price-group">
                <span
                  className="case-price"
                  aria-label={`Стоимость ${miniCase.priceStars} звёзд`}
                >
                  <span className="case-price-value">{miniCase.priceStars}</span>
                  <span className="case-price-unit" aria-hidden="true">
                    ★
                  </span>
                </span>
                <span className="case-price-hint">звёзд</span>
              </div>
              <button
                type="button"
                className="case-buy-button"
                onClick={() => onBuy(miniCase)}
                disabled={isProcessing}
              >
                {isProcessing ? 'Открываем…' : 'Купить'}
              </button>
            </div>
          </article>
        );
      })}
    </div>
  );
};

const App = () => {
  const [theme, refreshTheme] = useTelegramTheme();
  const { cases, loading, error, initDataMissing } = useMiniCases();
  const [paymentState, setPaymentState] = useState<PaymentState | null>(null);
  const [processingCaseId, setProcessingCaseId] = useState<string | null>(null);

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

  const handlePurchase = useCallback(async (miniCase: MiniCase) => {
    const webApp = initializeTelegramWebApp();
    const initData = webApp?.initData ?? '';

    if (!initData) {
      setPaymentState({
        caseId: miniCase.id,
        caseTitle: miniCase.title,
        status: 'error',
        message: 'initData недоступен. Откройте Mini App из Telegram и попробуйте снова.'
      });
      return;
    }

    setProcessingCaseId(miniCase.id);
    setPaymentState({
      caseId: miniCase.id,
      caseTitle: miniCase.title,
      status: 'loading',
      message: `Открываем платёж для кейса «${miniCase.title}»…`
    });

    try {
      const response = await fetch(
        `/api/miniapp/invoice?initData=${encodeURIComponent(initData)}`,
        {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ caseId: miniCase.id })
        }
      );

      if (!response.ok) {
        const fallback = await response.text().catch(() => '');
        let details = fallback;
        if (fallback) {
          try {
            const parsed: unknown = JSON.parse(fallback);
            if (isRecord(parsed) && typeof parsed.error === 'string') {
              details = parsed.error;
            }
          } catch {
            // ignore json parse failure
          }
        }
        throw new Error(
          details || `Не удалось создать счёт (код ${response.status})`
        );
      }

      const payload: unknown = await response.json();
      const invoiceResponse = parseInvoiceResponse(payload);

      if (!invoiceResponse) {
        throw new Error('Сервер вернул некорректные данные счёта.');
      }

      const { invoiceLink } = invoiceResponse;
      const invoiceStatus = await openInvoice(invoiceLink);
      switch (invoiceStatus) {
        case 'paid':
          setPaymentState({
            caseId: miniCase.id,
            caseTitle: miniCase.title,
            status: 'paid',
            message:
              `Платёж за кейс «${miniCase.title}» принят в Telegram. Ждите подтверждение после обработки вебхука.`
          });
          break;
        case 'cancelled':
          setPaymentState({
            caseId: miniCase.id,
            caseTitle: miniCase.title,
            status: 'cancelled',
            message: `Вы отменили оплату кейса «${miniCase.title}».`
          });
          break;
        case 'failed':
        default:
          setPaymentState({
            caseId: miniCase.id,
            caseTitle: miniCase.title,
            status: 'failed',
            message:
              `Telegram сообщил о сбое при оплате кейса «${miniCase.title}». Попробуйте ещё раз позже.`
          });
          break;
      }
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : 'Не удалось обработать покупку.';
      setPaymentState({
        caseId: miniCase.id,
        caseTitle: miniCase.title,
        status: 'error',
        message: `Не удалось завершить покупку кейса «${miniCase.title}»: ${message}`
      });
    } finally {
      setProcessingCaseId(null);
    }
  }, []);

  const casesContent = useMemo(
    () =>
      renderCasesContent(
        cases,
        loading,
        error,
        initDataMissing,
        handlePurchase,
        processingCaseId
      ),
    [cases, loading, error, initDataMissing, handlePurchase, processingCaseId]
  );

  return (
    <div className="app" style={{ backgroundImage: gradientBackground }}>
      <main className="content">
        <header className="hero">
          <span className="tag">Mini App</span>
          <h1>Витрина кейсов</h1>
          <p className="description">
            Подборка готовых сценариев, подарков и механик. Запускайте кампании быстрее и
            управляйте экономикой кейсов из одного места.
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

        <section className="cases-section">
          <div className="cases-header">
            <h2>Актуальные кейсы</h2>
            <p>Всегда свежие подборки с учётом параметров Stars и призовой экономики.</p>
          </div>

          {paymentState ? (
            <div
              className={`payment-status payment-status--${paymentState.status}`}
              role="status"
              aria-live="polite"
            >
              <span className="payment-status__state">
                Статус оплаты: <strong>{paymentState.status}</strong>
              </span>
              <span className="payment-status__message">{paymentState.message}</span>
            </div>
          ) : null}

          <div className="cases-content" aria-live="polite">
            {casesContent}
          </div>
        </section>
      </main>
    </div>
  );
};

export default App;
