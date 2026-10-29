// Non-component exports (contexts, hooks, constants, formatting), kept apart from component files.
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';

export const AuthContext = createContext(null);
export const useAuth = () => useContext(AuthContext);

export const ToastContext = createContext(() => {});
export const useToast = () => useContext(ToastContext);

/**
 * Loads data when `key` (a string) changes and when `reload()` is called. Keeps the last good data while reloading.
 * The latest `load` function is used, so callers can pass an inline arrow.
 */
export function useLoad(load, key) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const [version, setVersion] = useState(0);
  const latest = useRef(load);
  useLayoutEffect(() => {
    latest.current = load;
  });
  useEffect(() => {
    let current = true;
    latest.current().then(
      (data) => current && setState({ data, error: null, loading: false }),
      (error) => current && setState((s) => ({ ...s, error, loading: false })),
    );
    return () => { current = false; };
  }, [key, version]);
  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true, error: null }));
    setVersion((v) => v + 1);
  }, []);
  return { ...state, reload };
}

/** Amounts the user entered: shown as entered (up to 3 decimals). */
export const formatQuantity = (value) => Number(value).toLocaleString(undefined, { maximumFractionDigits: 3 });

/** Calculated amounts (unit conversions, sums): rounded to a precision a shopper can use. */
export function formatAmount(value) {
  const n = Number(value);
  const digits = Math.abs(n) >= 100 ? 0 : Math.abs(n) >= 10 ? 1 : 2;
  return n.toLocaleString(undefined, { maximumFractionDigits: digits });
}

export const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`;

export const CATEGORIES = ['dairy', 'vegetables', 'fruits', 'grains', 'meat', 'seafood', 'bakery', 'beverages',
  'condiments', 'frozen', 'snacks', 'other'];
export const UNITS = ['g', 'kg', 'ml', 'l', 'piece', 'pack', 'bunch', 'dozen'];
