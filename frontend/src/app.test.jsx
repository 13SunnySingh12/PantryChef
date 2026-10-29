import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { fakeBackend, ME, renderApp } from './test-utils.jsx';

const SIGN_IN = {
  'POST /auth/login': [200, { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 3600 }],
  [ME[0]]: ME[1],
};

const item = (over = {}) => ({
  id: 'i1', name: 'Spinach', category: 'vegetables', quantity: 2, quantityRemaining: 2, unit: 'bunch',
  purchasePrice: null, currencyCode: 'INR', purchaseDate: null, expiryDate: '2026-10-30',
  expiryStatus: 'expiring_soon', daysUntilExpiry: 1, version: 0, ...over,
});

describe('sign in', () => {
  test('wrong credentials show the generic error and keep the email', async () => {
    fakeBackend({ 'POST /auth/login': [401, { error: { code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect.' } }] });
    const { user } = await renderApp('/login', { signedIn: false });
    await user.type(screen.getByLabelText('Email'), 'cook@example.com');
    await user.type(screen.getByLabelText('Password'), 'nope nope');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('Email or password is incorrect.'));
    expect(screen.getByLabelText('Email').value).toBe('cook@example.com');
  });

  test('registration field errors appear under their fields', async () => {
    fakeBackend({ 'POST /auth/register': [400, { error: { code: 'VALIDATION_ERROR', message: 'Check the highlighted fields.',
      fieldErrors: { password: 'Use 8 to 72 characters.' } } }] });
    const { user } = await renderApp('/register', { signedIn: false });
    await user.type(screen.getByLabelText('Email'), 'cook@example.com');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    const password = screen.getByLabelText('Password');
    await waitFor(() => expect(password.getAttribute('aria-invalid')).toBe('true'));
    expect(document.getElementById(password.getAttribute('aria-describedby').split(' ').pop()).textContent)
      .toBe('Use 8 to 72 characters.');
  });

  test('an expired session returns to sign-in with a message', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /pantry-items': [401, { error: { code: 'UNAUTHENTICATED', message: 'Sign in to continue.' } }] });
    await renderApp('/pantry');
    expect(await screen.findByText('Your session ended. Sign in again to continue.')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
  });
});

describe('pantry', () => {
  test('lists items with a text expiry status, not colour alone', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /pantry-items': [200, [item(), item({ id: 'i2', name: 'Rice', expiryStatus: 'none', daysUntilExpiry: null, expiryDate: null })]] });
    await renderApp('/pantry');
    const list = await screen.findByRole('list', { name: 'Pantry items' });
    expect(within(list).getByText('Expires tomorrow')).toBeTruthy();
    expect(within(list).getByText('No expiry date')).toBeTruthy();
  });

  test('server validation errors show under the field and keep the input', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /pantry-items': [200, []],
      'POST /pantry-items': [400, { error: { code: 'VALIDATION_ERROR', message: 'Check the highlighted fields.', fieldErrors: { quantity: 'Must be more than 0.' } } }] });
    const { user } = await renderApp('/pantry');
    await user.click(await screen.findByRole('button', { name: 'Add item' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Name'), 'Milk');
    await user.type(within(dialog).getByLabelText('Quantity'), '0');
    await user.click(within(dialog).getByRole('button', { name: 'Add item' }));
    expect(await within(dialog).findByText('Must be more than 0.')).toBeTruthy();
    expect(within(dialog).getByLabelText('Name').value).toBe('Milk');
  });

  test('recording use sends one request key that is reused on retry', async () => {
    let attempts = 0;
    const calls = fakeBackend({ ...SIGN_IN, 'GET /pantry-items': [200, [item()]],
      'POST /pantry-items/i1/events': () => (++attempts === 1
        ? [503, { error: { code: 'X', message: 'Temporarily unavailable.' } }]
        : [201, { event: {}, item: item({ quantityRemaining: 1.5 }), replayed: false }]) });
    const { user } = await renderApp('/pantry');
    await user.click(await screen.findByRole('button', { name: 'Use' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('How much (bunch)'), '0.5');
    await user.click(within(dialog).getByRole('button', { name: 'Record use' }));
    expect(await within(dialog).findByText('Temporarily unavailable.')).toBeTruthy();
    await user.click(within(dialog).getByRole('button', { name: 'Record use' }));
    expect(await screen.findByText('Use recorded.')).toBeTruthy();
    const events = calls.filter((c) => c.path === '/pantry-items/i1/events');
    expect(events).toHaveLength(2);
    expect(events[0].body).toEqual({ type: 'used', quantity: 0.5 });
    expect(events[0].headers['Idempotency-Key']).toBe(events[1].headers['Idempotency-Key']);
  });
});

describe('recipes', () => {
  const suggestion = {
    recipeId: 'r1', title: '<img src=x onerror=alert(1)> Palak Paneer',
    source: { name: 'Wikibooks Cookbook', url: 'https://en.wikibooks.org/w/index.php?title=X&oldid=1', license: 'CC BY-SA 4.0',
      attribution: '"Palak Paneer" from the Wikibooks Cookbook, CC BY-SA 4.0.' },
    cuisine: 'Indian', servings: 4, dietLabels: ['vegetarian'], allergenDataComplete: false,
    matchedIngredients: ['Spinach'], missingIngredients: ['Paneer'], usesExpiringItems: ['spinach'], ranking: {},
  };

  test('show the source citation and treat recipe text as text', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /recipes/suggestions': [200, { disclaimer: 'Always check ingredient labels.', suggestions: [suggestion], excludedByPreferences: 2, reason: null }] });
    await renderApp('/recipes');
    const link = await screen.findByRole('link', { name: /Palak Paneer/ });
    expect(link.getAttribute('href')).toBe(suggestion.source.url);
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText(suggestion.source.attribution)).toBeTruthy();
    expect(screen.getByText('Allergen information incomplete')).toBeTruthy();
    expect(screen.getByText(/Always check ingredient labels\. 2 recipes were left out/)).toBeTruthy();
  });

  test('explain an empty result instead of showing nothing', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /recipes/suggestions': [200, { disclaimer: 'x', suggestions: [], excludedByPreferences: 5, reason: 'no_safe_match' }] });
    await renderApp('/recipes');
    expect(await screen.findByRole('heading', { name: 'No recipe fits your settings' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Review settings' })).toBeTruthy();
  });
});

describe('meal plan', () => {
  const plan = {
    id: 'p1', startDate: '2026-10-29', endDate: '2026-11-04', status: 'partial', createdAt: 'x', groceryCalculatedAt: 'x',
    disclaimer: 'Always check ingredient labels.',
    entries: ['2026-10-29'].flatMap((date) => [
      { date, mealPeriod: 'breakfast', recipeId: 'r1', title: 'Masala Omelette', servings: 2, sourceUrl: 'https://example.org/r1' },
      { date, mealPeriod: 'lunch', recipeId: null, unavailableReason: 'No main-course recipe fits your diet and allergen settings.' },
      { date, mealPeriod: 'dinner', recipeId: 'r2', title: 'Dal', servings: null, sourceUrl: 'https://example.org/r2' },
    ]),
    grocery: [{ id: 'g1', ingredient: 'onion', quantity: 2, unit: 'piece', unresolved: false, checked: false },
      { id: 'g2', ingredient: 'salt', quantity: null, unit: null, unresolved: true, checked: false }],
  };

  test('shows unavailable slots with their reason and unresolved amounts', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /meal-plans': [200, [{ id: 'p1', startDate: '2026-10-29', plannedSlots: 2 }]], 'GET /meal-plans/p1': [200, plan] });
    await renderApp('/plans');
    expect(await screen.findByText('No meal: No main-course recipe fits your diet and allergen settings.')).toBeTruthy();
    expect(screen.getByText('Servings not stated.', { exact: false })).toBeTruthy();
    expect(screen.getByText('amount not calculated')).toBeTruthy();
  });

  test('a failed check-off is undone and explained', async () => {
    fakeBackend({ ...SIGN_IN, 'GET /meal-plans': [200, [{ id: 'p1', startDate: '2026-10-29', plannedSlots: 2 }]], 'GET /meal-plans/p1': [200, plan],
      'PATCH /grocery-items/g1': [503, { error: { code: 'X', message: 'Could not save. Try again.' } }] });
    const { user } = await renderApp('/plans');
    const onion = await screen.findByRole('checkbox', { name: /onion/ });
    await user.click(onion);
    expect(await screen.findByText('Could not save. Try again.')).toBeTruthy();
    expect(onion.checked).toBe(false);
  });
});

describe('meal plan start date (A-10)', () => {
  test('no date sends no body, a chosen date is sent as typed, and a server error shows under the field', async () => {
    let attempt = 0;
    const calls = fakeBackend({ ...SIGN_IN, 'GET /meal-plans': [200, []],
      'POST /meal-plans': () => (++attempt === 1
        ? [400, { error: { code: 'VALIDATION_ERROR', message: 'Check the highlighted fields and try again.',
          fieldErrors: { startDate: 'Choose a date within the next 7 days.' } } }]
        : [201, { id: 'p9' }]),
      'GET /meal-plans/p9': [200, { id: 'p9', startDate: '2026-11-02', endDate: '2026-11-08', status: 'complete', entries: [], grocery: [],
        disclaimer: 'x', createdAt: 'x', groceryCalculatedAt: 'x' }] });
    const { user } = await renderApp('/plans');
    const date = await screen.findByLabelText('Start date (optional)');
    await user.type(date, '2026-12-25');
    await user.click(screen.getByRole('button', { name: 'Plan 7 days' }));
    expect(await screen.findByText('Choose a date within the next 7 days.')).toBeTruthy();
    expect(date.getAttribute('aria-invalid')).toBe('true');
    const first = calls.filter((c) => c.method === 'POST' && c.path === '/meal-plans')[0];
    expect(first.body).toEqual({ startDate: '2026-12-25' });

    await user.clear(date);
    await user.click(screen.getByRole('button', { name: 'Plan 7 days' }));
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && c.path === '/meal-plans')).toHaveLength(2));
    const second = calls.filter((c) => c.method === 'POST' && c.path === '/meal-plans')[1];
    expect(second.body).toBeUndefined();
    expect(second.headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);
  });
});

describe('dashboard', () => {
  test('reports nutrition as unknown and offers the chart data as a table', { timeout: 30000 }, async () => {
    // The page is lazy-loaded; on a cold run transforming the chart library can outlast findBy's wait, so load it first.
    await import('./pages/DashboardPage.jsx');
    fakeBackend({ ...SIGN_IN, 'GET /dashboard/summary': [200, {
      window: { from: '2026-10-28', to: '2026-10-29', timezone: 'Asia/Kolkata' }, currency: 'INR',
      foodSaved: { items: 1, events: 1, quantities: [{ unit: 'g', amount: 200 }], usedAfterExpiry: 1, usedWithoutExpiryDate: 0 },
      thrownAway: { items: 0, events: 0, quantities: [] }, moneySaved: { totals: [{ currency: 'INR', amount: 90 }], eventsWithUnknownPrice: 1 },
      daily: [{ date: '2026-10-28', savedEvents: 0, discardedEvents: 0, moneySaved: 0 }, { date: '2026-10-29', savedEvents: 1, discardedEvents: 0, moneySaved: 90 }],
      nutrition: { planId: 'p1', plannedMeals: 3, mealsWithNutrition: 0, complete: false, total: null, days: [{ date: '2026-10-29', plannedMeals: 3, mealsWithNutrition: 0, total: null }] },
      note: 'Estimates from what you recorded.' }] });
    await renderApp('/dashboard');
    // the dashboard chunk (charts) is lazy-loaded, which is slow in jsdom
    expect(await screen.findByText(/^Unknown: the recipe sources do not include nutrition/, {}, { timeout: 15000 })).toBeTruthy();
    expect(screen.getByText('1 use without a price is not included')).toBeTruthy();
    expect(screen.getByText(/1 use after the expiry date and 0 uses of items/)).toBeTruthy();
    expect(screen.getByText('1 item')).toBeTruthy();
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(2);
  });
});

describe('preferences', () => {
  test('saves the chosen diets and allergens', async () => {
    const calls = fakeBackend({ ...SIGN_IN, 'GET /preferences': [200, { diets: [], allergens: ['dairy'] }],
      'PUT /preferences': (body) => [200, body] });
    const { user } = await renderApp('/preferences');
    await user.click(await screen.findByRole('checkbox', { name: 'Vegan' }));
    await user.click(screen.getByRole('checkbox', { name: 'Peanuts' }));
    await user.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText(/Preferences saved/);
    expect(calls.find((c) => c.method === 'PUT').body).toEqual({ diets: ['vegan'], allergens: ['dairy', 'peanuts'] });
  });
});

describe('journey (ROAD-028)', () => {
  test('register, add food, see it expiring, plan the week and tick off groceries', { timeout: 30000 }, async () => {
    // A stateful fake backend: each step only works if the earlier ones sent the right data.
    const pantry = [];
    let plan = null;
    const calls = fakeBackend({
      'POST /auth/register': [201, { id: 'u1', email: 'new@example.com' }],
      ...SIGN_IN,
      'GET /pantry-items': () => [200, structuredClone(pantry)],
      'POST /pantry-items': (body) => {
        pantry.push(item({ id: 'i1', name: body.name, quantity: body.quantity, quantityRemaining: body.quantity, unit: body.unit,
          expiryDate: body.expiryDate, expiryStatus: 'expiring_soon', daysUntilExpiry: 1 }));
        return [201, structuredClone(pantry[0])];
      },
      'GET /expiry-alerts': () => [200, pantry.map((p) => ({ id: `a-${p.id}`, type: 'expiring_soon', alertDate: '2026-10-29', item: p }))],
      'GET /meal-plans': () => [200, plan ? [{ id: plan.id, startDate: plan.startDate, plannedSlots: 1 }] : []],
      'POST /meal-plans': () => {
        plan = { id: 'p1', startDate: '2026-10-29', endDate: '2026-11-04', status: 'partial', createdAt: 'x', groceryCalculatedAt: 'x',
          disclaimer: 'Always check ingredient labels.',
          entries: [{ date: '2026-10-29', mealPeriod: 'breakfast', recipeId: 'r1', title: `${pantry[0].name} Omelette`, servings: 2, sourceUrl: 'https://example.org/r1' }],
          grocery: [{ id: 'g1', ingredient: 'eggs', quantity: 2, unit: 'piece', unresolved: false, checked: false }] };
        return [201, structuredClone(plan)];
      },
      'GET /meal-plans/p1': () => [200, structuredClone(plan)],
      'PATCH /grocery-items/g1': (body) => {
        plan.grocery[0].checked = body.checked;
        return [200, structuredClone(plan.grocery[0])];
      },
    });
    const { user } = await renderApp('/register', { signedIn: false });
    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    await user.type(screen.getByLabelText('Password'), 'long enough pw');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await user.click(await screen.findByRole('button', { name: 'Add item' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Name'), 'Spinach');
    await user.type(within(dialog).getByLabelText('Quantity'), '2');
    await user.selectOptions(within(dialog).getByLabelText('Unit'), 'bunch');
    await user.type(within(dialog).getByLabelText('Expiry date (optional)'), '2026-10-30');
    await user.click(within(dialog).getByRole('button', { name: 'Add item' }));
    expect(await within(await screen.findByRole('list', { name: 'Pantry items' })).findAllByText('Spinach')).not.toHaveLength(0);
    expect(calls.find((c) => c.method === 'POST' && c.path === '/pantry-items').body)
      .toMatchObject({ name: 'Spinach', quantity: 2, unit: 'bunch', expiryDate: '2026-10-30' });

    await user.click(screen.getByRole('link', { name: 'Expiring' }));
    expect(await screen.findByRole('heading', { name: 'Spinach' })).toBeTruthy();
    expect(screen.getByText('Expires tomorrow')).toBeTruthy();

    await user.click(screen.getByRole('link', { name: 'Meal plan' }));
    await user.click(await screen.findByRole('button', { name: 'Plan 7 days' }));
    expect(await screen.findByText('Spinach Omelette')).toBeTruthy();
    expect(calls.find((c) => c.method === 'POST' && c.path === '/meal-plans').headers['Idempotency-Key']).toBeTruthy();

    const eggs = await screen.findByRole('checkbox', { name: /eggs/ });
    await user.click(eggs);
    await waitFor(() => expect(eggs.checked).toBe(true));
    expect(calls.find((c) => c.method === 'PATCH').body).toEqual({ checked: true });
  });
});
