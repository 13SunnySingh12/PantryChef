import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { WarningCircle } from '@phosphor-icons/react';
import { ToastContext } from './shared.js';

/** Label above, hint and error below, error announced and linked to the input. */
export function Field({ label, error, hint, children, ...inputProps }) {
  const id = useId();
  const described = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined;
  const control = children
    ? children({ id, 'aria-invalid': error ? 'true' : undefined, 'aria-describedby': described })
    : <input id={id} aria-invalid={error ? 'true' : undefined} aria-describedby={described} {...inputProps} />;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {control}
      {hint && <span id={`${id}-hint`} className="hint">{hint}</span>}
      {error && <span id={`${id}-error`} className="error">{error}</span>}
    </div>
  );
}

export function ErrorNotice({ error }) {
  if (!error) return null;
  return (
    <div className="notice notice-error" role="alert">
      <WarningCircle size={20} weight="bold" aria-hidden="true" />
      <span>
        {error.message}
        {error.requestId && <span className="small"> (reference {error.requestId.slice(0, 8)})</span>}
      </span>
    </div>
  );
}

export function Loading({ rows = 3, label = 'Loading' }) {
  return (
    <div className="stack" role="status" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" />)}
    </div>
  );
}

export function Empty({ title, children }) {
  return (
    <div className="empty panel">
      <h2>{title}</h2>
      {children}
    </div>
  );
}

/** Native <dialog> (focus handling, Escape and backdrop come from the browser). */
export function Dialog({ open, onClose, title, children }) {
  const ref = useRef(null);
  useEffect(() => {
    const dialog = ref.current;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  return (
    <dialog ref={ref} onClose={onClose} aria-labelledby={title ? `${title}-title` : undefined}>
      {open && (
        <div className="dialog-body">
          <h2 id={`${title}-title`}>{title}</h2>
          {children}
        </div>
      )}
    </dialog>
  );
}

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const show = useCallback((text) => {
    const id = crypto.randomUUID();
    setToasts((list) => [...list, { id, text }]);
    setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 3500);
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        <AnimatePresence initial={false}>
          {toasts.map((t) => (
            <motion.div
              key={t.id}
              className="toast"
              initial={{ opacity: 0, transform: 'translateY(8px)' }}
              animate={{ opacity: 1, transform: 'translateY(0px)' }}
              exit={{ opacity: 0, transition: { duration: 0.15 } }}
              transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
            >
              {t.text}
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  );
}
