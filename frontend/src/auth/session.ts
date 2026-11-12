import type { AuthUser } from '../types/auth';

interface AuthSnapshot {
  accessToken: string;
  user: AuthUser;
  receivedAt: number;
}

type AuthListener = (snapshot: AuthSnapshot | null) => void;

let snapshot: AuthSnapshot | null = null;
const listeners = new Set<AuthListener>();
const channel = typeof BroadcastChannel !== 'undefined'
  ? new BroadcastChannel('ai-interview-auth')
  : null;

function notify(): void {
  listeners.forEach((listener) => listener(snapshot));
}

channel?.addEventListener('message', (event: MessageEvent<{ type: string; snapshot?: AuthSnapshot }>) => {
  if (event.data?.type === 'session' && event.data.snapshot) {
    snapshot = event.data.snapshot;
    notify();
    return;
  }
  if (event.data?.type === 'logout') {
    snapshot = null;
    notify();
  }
});

export function getAccessToken(): string | null {
  return snapshot?.accessToken ?? null;
}

export function getAuthSnapshot(): AuthSnapshot | null {
  return snapshot;
}

export function setAuthSession(accessToken: string, user: AuthUser): void {
  snapshot = { accessToken, user, receivedAt: Date.now() };
  notify();
  channel?.postMessage({ type: 'session', snapshot });
}

export function clearAuthSession(broadcast = true): void {
  snapshot = null;
  notify();
  if (broadcast) {
    channel?.postMessage({ type: 'logout' });
  }
}

export function subscribeAuth(listener: AuthListener): () => void {
  listeners.add(listener);
  listener(snapshot);
  return () => listeners.delete(listener);
}

export function waitForAccessToken(timeoutMs = 1000): Promise<string | null> {
  const existing = getAccessToken();
  if (existing) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve) => {
    const timeout = window.setTimeout(() => {
      unsubscribe();
      resolve(getAccessToken());
    }, timeoutMs);
    const unsubscribe = subscribeAuth((next) => {
      if (next?.accessToken) {
        window.clearTimeout(timeout);
        unsubscribe();
        resolve(next.accessToken);
      }
    });
  });
}
