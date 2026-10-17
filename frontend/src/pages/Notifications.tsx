import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Badge, Empty, ErrorBanner, LiveIndicator, PageHeader, Panel, Spinner } from '../components/ui';
import { fmtTime, humanize } from '../format';
import { usePolling } from '../hooks/usePolling';
import type { Tone } from '../saga';

const POLL = 4000;
const EVENT_TONE: Record<string, Tone> = { ORDER_CREATED: 'info', ORDER_CONFIRMED: 'good', ORDER_CANCELLED: 'critical' };

export function Notifications() {
  const { data, error, loading, refresh, lastUpdated } = usePolling(() => api.notifications.list(200), POLL);
  const [type, setType] = useState('');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<string | null>(null);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (data ?? []).filter(
      (n) =>
        (!type || n.eventType === type) &&
        (!q || n.orderId.includes(q) || n.customerId.toLowerCase().includes(q) || n.subject.toLowerCase().includes(q)),
    );
  }, [data, type, query]);

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="notification-service · rendered from order.events. Idempotent: a redelivered event never produces a second email."
        actions={<LiveIndicator lastUpdated={lastUpdated} intervalMs={POLL} />}
      />
      <ErrorBanner error={error} onRetry={refresh} />
      <Panel>
        <div className="toolbar">
          <input className="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search order ID, customer or subject" aria-label="Search notifications" />
          <div className="segmented" role="group" aria-label="Event type">
            {['', 'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED'].map((t) => (
              <button key={t} className={type === t ? 'active' : ''} onClick={() => setType(t)}>
                {t ? humanize(t.replace('ORDER_', '')) : 'All'}
              </button>
            ))}
          </div>
        </div>
        {loading && !data ? (
          <Spinner />
        ) : rows.length === 0 ? (
          <Empty>No notifications.</Empty>
        ) : (
          <ul className="notes">
            {rows.map((n) => (
              <li key={n.id} className="note">
                <button className="note-toggle" onClick={() => setOpen(open === n.id ? null : n.id)} aria-expanded={open === n.id}>
                  <div className="note-head">
                    <Badge tone={EVENT_TONE[n.eventType] ?? 'neutral'}>{humanize(n.eventType)}</Badge>
                    <span className="note-subject">{n.subject}</span>
                    <span className="muted nowrap">
                      {n.channel} → {n.customerId} · {fmtTime(n.createdAt)}
                    </span>
                  </div>
                </button>
                {open === n.id && (
                  <div className="note-expanded">
                    <pre className="note-body">{n.body}</pre>
                    <Link to={`/orders/${n.orderId}`}>Open order {n.orderId.slice(0, 8)} →</Link>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </>
  );
}
