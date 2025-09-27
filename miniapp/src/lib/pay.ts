import { initializeTelegramWebApp } from './tg';

export type InvoiceStatus = 'paid' | 'cancelled' | 'failed';

export const openInvoice = async (invoiceLink: string): Promise<InvoiceStatus> => {
  const webApp = initializeTelegramWebApp();
  if (!webApp || typeof webApp.openInvoice !== 'function') {
    throw new Error('Платежи Telegram недоступны в этом окружении');
  }

  return await new Promise<InvoiceStatus>((resolve, reject) => {
    let settled = false;
    const resolveOnce = (status: InvoiceStatus) => {
      if (settled) {
        return;
      }
      settled = true;
      resolve(status);
    };

    const handleReject = (reason: unknown) => {
      if (settled) {
        return;
      }
      settled = true;
      reject(
        reason instanceof Error
          ? reason
          : new Error('Не удалось открыть окно платежа в Telegram')
      );
    };

    try {
      const openResult = webApp.openInvoice(invoiceLink, resolveOnce);
      Promise.resolve(openResult)
        .then((opened) => {
          if (opened === false) {
            handleReject(new Error('Не удалось открыть окно платежа в Telegram'));
          }
        })
        .catch(handleReject);
    } catch (error) {
      handleReject(error);
    }
  });
};
