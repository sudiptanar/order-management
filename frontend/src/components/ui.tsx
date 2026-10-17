import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { humanize } from '../format';
import type { Tone } from '../saga';

const TONE_ICON: Record<Tone, string> = {
  good: '✓',
  warning: '!',
  serious: '↺',
  critical: '✕',
  info: '•',
  neutral: '•',
};

/** Status always ships with an icon and a label, never color alone. */
export function Badge({ tone, children, title }: { tone: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge tone-${tone}`} title={title}>
      <span className="badge-icon" aria-hidden>
        {TONE_ICON[tone]}
      </span>
      {children}
    </span>
  );
}

export function StatusBadge({ value, tones }: { value: string | null | undefined; tones: Record<string, Tone> }) {
  if (!value) return <span className="muted">—</span>;
  return <Badge tone={tones[value] ?? 'neutral'}>{humanize(value)}</Badge>;
}

export function Panel({
  title,
  actions,
  children,
  className = '',
  subtitle,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {(title || actions) && (
        <header className="panel-head">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p className="panel-sub">{subtitle}</p>}
          </div>
          {actions && <div className="panel-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-sub">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function ErrorBanner({ error, onRetry }: { error: string | null | undefined; onRetry?: () => void }) {
  if (!error) return null;
  return (
    <div className="banner banner-critical" role="alert">
      <span className="badge-icon" aria-hidden>
        ✕
      </span>
      <span>{error}</span>
      {onRetry && (
        <button className="btn btn-ghost btn-sm" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function Spinner() {
  return <span className="spinner" aria-label="Loading" />;
}

export function LiveIndicator({ lastUpdated, intervalMs }: { lastUpdated: Date | null; intervalMs: number }) {
  return (
    <span className="live" title={lastUpdated ? `Updated ${lastUpdated.toLocaleTimeString()}` : 'Waiting for data'}>
      <span className="live-dot" aria-hidden />
      Live · every {intervalMs / 1000}s
    </span>
  );
}

export function Mono({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <code className="mono" title={title}>
      {children}
    </code>
  );
}

export function CopyId({ id }: { id: string }) {
  const toast = useToast();
  return (
    <button
      className="copy-id"
      title={`${id} — click to copy`}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        navigator.clipboard?.writeText(id).then(() => toast('Copied to clipboard', 'good'));
      }}
    >
      {id.slice(0, 8)}
    </button>
  );
}

// ---------------------------------------------------------------- toasts

type ToastFn = (message: string, tone?: Tone) => void;
const ToastContext = createContext<ToastFn>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string; tone: Tone }[]>([]);
  const push = useCallback<ToastFn>((message, tone = 'neutral') => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, message, tone }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast tone-${t.tone}`}>
            <span className="badge-icon" aria-hidden>
              {TONE_ICON[t.tone]}
            </span>
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);
