import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import { refreshAccessToken } from '../api/request';
import {
  clearAuthSession,
  getAuthSnapshot,
  setAuthSession,
  subscribeAuth,
} from './session';
import type { AuthUser } from '../types/auth';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  isAdmin: boolean;
  login: (phone: string, code: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(getAuthSnapshot()?.user ?? null);
  const [loading, setLoading] = useState(true);

  useEffect(() => subscribeAuth((snapshot) => {
    setUser(snapshot?.user ?? null);
  }), []);

  useEffect(() => {
    let cancelled = false;
    refreshAccessToken()
      .catch(() => null)
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    isAdmin: user?.role === 'ADMIN',
    async login(phone: string, code: string) {
      const response = await authApi.login(phone, code);
      setAuthSession(response.accessToken, response.user);
    },
    async logout() {
      try {
        await authApi.logout();
      } finally {
        clearAuthSession();
      }
    },
    async refresh() {
      await refreshAccessToken(true);
    },
  }), [loading, user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
