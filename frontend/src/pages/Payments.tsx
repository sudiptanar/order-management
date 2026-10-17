import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, LiveIndicator, PageHeader, Panel, Spinner, StatusBadge } from '../components/ui';
import { fmtMoney, fmtTime } from '../format';
import { usePolling } from '../hooks/usePolling';
import { PAYMENT_STATUS_TONE } from '../saga';

const POLL = 4000;
const STATUSES = ['', 'AUTHORIZED', 'DECLINED', 'REFUNDED'];

export function Payments() {
  const { data, error, loading, refresh, lastUpdated } = usePolling(() => api.payments.list(200), POLL);
  const [status, setStatus] = useState('');
  const [query, setQuery] = useState('');

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (data ?? []).filter(
      (p) =>
        (!status || p.status === status) &&
        (!q || p.orderId.includes(q) || p.customerId.toLowerCase().includes(q) || (p.authorizationRef ?? '').toLowerCase().includes(q)),
    );
  }, [data, status, query]);

  const totals = useMemo(() => {
    const t: Record<string, { n: number; amount: number }> = {};
    for (const p of data ?? []) {
      t[p.status] ??= { n: 0, amount: 0 };
      t[p.status].n++;
      t[p.status].amount += Number(p.amount);
    }
    return t;
  }, [data]);

  return (
    <>
      <PageHeader
        title="Payments"
        subtitle="payment-service · one payment per order, enforced by a unique constraint. Orders over $10,000 are declined."
        actions={<LiveIndicator lastUpdated={lastUpdated} intervalMs={POLL} />}
      />
      <ErrorBanner error={error} onRetry={refresh} />

      <div className="stats">
        {(['AUTHORIZED', 'DECLINED', 'REFUNDED'] as const).map((s) => (
          <div className="stat" key={s}>
            <div className="stat-label">
              <StatusBadge value={s} tones={PAYMENT_STATUS_TONE} />
            </div>
            <div className="stat-value">{totals[s]?.n ?? 0}</div>
            <div className="stat-hint">{fmtMoney(totals[s]?.amount ?? 0)}</div>
          </div>
        ))}
      </div>

      <Panel>
        <div className="toolbar">
          <input className="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search order ID, customer or auth ref" aria-label="Search payments" />
          <div className="segmented" role="group" aria-label="Status">
            {STATUSES.map((s) => (
              <button key={s} className={status === s ? 'active' : ''} onClick={() => setStatus(s)}>
                {s ? s.charAt(0) + s.slice(1).toLowerCase() : 'All'}
              </button>
            ))}
          </div>
        </div>
        {loading && !data ? (
          <Spinner />
        ) : rows.length === 0 ? (
          <Empty>No payments.</Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Order</th>
                  <th>Customer</th>
                  <th className="num">Amount</th>
                  <th>Status</th>
                  <th>Auth ref</th>
                  <th>Reason</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <Link className="mono-sm" to={`/orders/${p.orderId}`}>
                        {p.orderId.slice(0, 8)}
                      </Link>
                    </td>
                    <td>{p.customerId}</td>
                    <td className="num">{fmtMoney(p.amount)}</td>
                    <td>
                      <StatusBadge value={p.status} tones={PAYMENT_STATUS_TONE} />
                    </td>
                    <td className="mono-sm">{p.authorizationRef ?? '—'}</td>
                    <td className="muted ellipsis" title={p.failureReason ?? ''}>
                      {p.failureReason ?? '—'}
                    </td>
                    <td className="muted nowrap">{fmtTime(p.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </>
  );
}
