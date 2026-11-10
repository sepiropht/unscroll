export function storageGet(keys: string | string[] | null): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    chrome.storage.local.get(keys, (items) => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve(items as Record<string, unknown>);
    });
  });
}

export function storageSet(items: Record<string, unknown>): Promise<void> {
  return new Promise((resolve, reject) => {
    chrome.storage.local.set(items, () => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve();
    });
  });
}

export function queryTabs(info: chrome.tabs.QueryInfo): Promise<chrome.tabs.Tab[]> {
  return new Promise((resolve, reject) => {
    chrome.tabs.query(info, (tabs) => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve(tabs);
    });
  });
}

export function updateTab(tabId: number, url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    chrome.tabs.update(tabId, { url }, () => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve();
    });
  });
}

export function queryIdle(seconds: number): Promise<chrome.idle.IdleState> {
  return new Promise((resolve) => {
    chrome.idle.queryState(seconds, (state) => {
      if (chrome.runtime.lastError) resolve('active');
      else resolve(state);
    });
  });
}

export function lastFocusedWindow(): Promise<chrome.windows.Window | null> {
  return new Promise((resolve) => {
    chrome.windows.getLastFocused({ windowTypes: ['normal'] }, (win) => {
      if (chrome.runtime.lastError || !win) resolve(null);
      else resolve(win);
    });
  });
}

export function sendMessage<T>(message: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(message, (response: T) => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve(response);
    });
  });
}

export function notify(id: string, title: string, message: string): Promise<void> {
  return new Promise((resolve) => {
    chrome.notifications.create(
      id,
      {
        type: 'basic',
        iconUrl: chrome.runtime.getURL('icons/icon128.png'),
        title,
        message,
      },
      () => {
        resolve();
      },
    );
  });
}
