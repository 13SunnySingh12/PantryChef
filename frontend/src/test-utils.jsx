import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { AppRoutes } from './App.jsx';
import { AuthProvider } from './auth.jsx';
import { setToken } from './api.js';
import { ToastProvider } from './ui.jsx';

/**
 * A fake backend: `routes` maps "METHOD /path" to a status and body (or a function of the request body).
 * Every request is recorded so tests can check what the page sent.
 */
export function fakeBackend(routes) {
  const calls = [];
  globalThis.fetch = vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET';
    const path = url.replace('/api/v1', '');
    const body = options.body ? JSON.parse(options.body) : undefined;
    calls.push({ method, path, body, headers: options.headers || {} });
    const route = routes[`${method} ${path}`] ?? routes[`${method} ${path.split('?')[0]}`];
    const [status, data] = typeof route === 'function' ? route(body) : route ?? [404, { error: { code: 'NOT_FOUND', message: 'Not found.' } }];
    return { ok: status < 400, status, json: async () => data };
  });
  return calls;
}

export const ME = ['GET /auth/me', [200, { id: 'u1', email: 'cook@example.com' }]];

/** Renders the app at `path`, already signed in unless `signedIn` is false. */
export async function renderApp(path, { signedIn = true } = {}) {
  const user = userEvent.setup();
  const utils = render(
    <AuthProvider>
      <ToastProvider>
        <MemoryRouter initialEntries={[signedIn ? '/login' : path]}>
          <AppRoutes />
        </MemoryRouter>
      </ToastProvider>
    </AuthProvider>,
  );
  if (signedIn) {
    setToken(null);
    await user.type(utils.getByLabelText('Email'), 'cook@example.com');
    await user.type(utils.getByLabelText('Password'), 'long enough pw');
    await user.click(utils.getByRole('button', { name: 'Sign in' }));
    if (path !== '/pantry') {
      await user.click(await utils.findByRole('link', { name: linkName(path) }));
    }
  }
  return { user, ...utils };
}

const linkName = (path) => ({ '/alerts': 'Expiring', '/recipes': 'Recipes', '/plans': 'Meal plan',
  '/dashboard': 'Dashboard', '/preferences': 'Diet & allergies' })[path];
