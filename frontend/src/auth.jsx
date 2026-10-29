import { useEffect, useState } from 'react';
import { api, onSessionExpired, setToken } from './api.js';
import { AuthContext } from './shared.js';

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    onSessionExpired(() => {
      setUser(null);
      setExpired(true);
    });
  }, []);

  async function login(email, password) {
    const { accessToken } = await api('/auth/login', { method: 'POST', body: { email, password } });
    setToken(accessToken);
    setUser(await api('/auth/me'));
    setExpired(false);
  }

  async function register(email, password) {
    await api('/auth/register', { method: 'POST', body: { email, password } });
    await login(email, password);
  }

  async function logout() {
    await api('/auth/logout', { method: 'POST' }).catch(() => {});
    setToken(null);
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, expired, login, register, logout }}>{children}</AuthContext.Provider>
  );
}
