import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, tokenStore } from '../api/client';
import { useMe } from '../api/hooks';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => tokenStore.get());
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
    async (email, password) => {
      const result = await api('/auth/login', { method: 'POST', body: { email, password } });
      tokenStore.set(result.token);
      qc.clear();
      setToken(result.token);
    },
    [qc],
  );

  const value = useMemo(
    () => ({
      token,
      me: meQuery.data,
      login,
      logout,
      /** Effective role in a team, for showing or hiding controls. The server enforces the real rules. */
      roleIn: (teamId) => meQuery.data?.teams.find((t) => t.id === teamId)?.role,
    }),
    [token, meQuery.data, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
