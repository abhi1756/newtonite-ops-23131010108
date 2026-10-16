import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, tokenStore } from '../api/client';
import { useMe } from '../api/hooks';
import type { Me, TeamRole } from '../api/types';

interface AuthState {
  token: string | null;
  me: Me | undefined;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** Effective role in a team, for showing or hiding controls. The server enforces the real rules. */
  roleIn: (teamId: number) => TeamRole | undefined;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => tokenStore.get());
  const qc = useQueryClient();
  const meQuery = useMe(token !== null);

  const logout = useCallback(() => {
    tokenStore.clear();
    setToken(null);
    qc.clear();
  }, [qc]);

  useEffect(() => {
    // The API client fires this when the server answers 401 (expired or invalid token).
    const onLogout = () => logout();
    window.addEventListener('auth:logout', onLogout);
    return () => window.removeEventListener('auth:logout', onLogout);
  }, [logout]);

  const login = useCallback(
    async (email: string, password: string) => {
      const result = await api<{ token: string }>('/auth/login', { method: 'POST', body: { email, password } });
      tokenStore.set(result.token);
      qc.clear();
      setToken(result.token);
    },
    [qc],
  );

  const value = useMemo<AuthState>(
    () => ({
      token,
      me: meQuery.data,
      login,
      logout,
      roleIn: (teamId) => meQuery.data?.teams.find((t) => t.id === teamId)?.role,
    }),
    [token, meQuery.data, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
