import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { OrdersTable } from '../components/OrdersTable';
import { Empty, ErrorBanner, LiveIndicator, PageHeader, Panel, Spinner } from '../components/ui';
import { usePolling } from '../hooks/usePolling';
import { isInFlight, looksStuck, SAGA_STATE_META, SAGA_STATES } from '../saga';

const POLL = 3000;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const QUICK_FILTERS = [
  { key: '', label: 'All' },
  { key: 'inflight', label: 'In flight' },
  { key: 'stuck', label: 'Stuck' },
  { key: 'confirmed', label: 'Confirmed' },
  { key: 'cancelled', label: 'Cancelled' },
];

export function Orders() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(100);
  const filter = params.get('filter') ?? '';
  const saga = params.get('saga') ?? '';

  const { data, error, loading, refresh, lastUpdated } = usePolling(() => api.orders.list(limit), POLL, [limit]);

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (data ?? []).filter((o) => {
      if (saga && o.sagaState !== saga) return false;
      if (filter === 'inflight' && !isInFlight(o)) return false;
      if (filter === 'stuck' && !looksStuck(o)) return false;
      if (filter === 'confirmed' && o.status !== 'CONFIRMED') return false;
      if (filter === 'cancelled' && o.status !== 'CANCELLED') return false;
      if (!q) return true;
      return (
        o.orderId.toLowerCase().includes(q) ||
        o.customerId.toLowerCase().includes(q) ||
        o.items.some((i) => i.productId.toLowerCase().includes(q))
      );
    });
  }, [data, query, filter, saga]);

  const lookup = (e: FormEvent) => {
    e.preventDefault();
    if (UUID_RE.test(query.trim())) navigate(`/orders/${query.trim()}`);
  };

  return (
    <>
      <PageHeader
        title="Orders"
        subtitle="Every order and where its saga stands. Click a row to trace it across services."
        actions={
          <>
            <LiveIndicator lastUpdated={lastUpdated} intervalMs={POLL} />
            <Link to="/orders/new" className="btn btn-primary">
              + Place order
            </Link>
          </>
        }
      />
      <ErrorBanner error={error} onRetry={refresh} />

      <Panel>
        <div className="toolbar">
          <form onSubmit={lookup} className="search">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search order ID, customer or product - paste a full order ID and press Enter to open it"
              aria-label="Search orders"
            />
          </form>
          <div className="segmented" role="group" aria-label="Quick filter">
            {QUICK_FILTERS.map((f) => (
              <button key={f.key} className={filter === f.key ? 'active' : ''} onClick={() => setParam('filter', f.key)}>
                {f.label}
              </button>
            ))}
          </div>
          <select value={saga} onChange={(e) => setParam('saga', e.target.value)} aria-label="Saga state">
            <option value="">Any saga state</option>
            {SAGA_STATES.map((s) => (
              <option key={s} value={s}>
                {SAGA_STATE_META[s].label}
              </option>
            ))}
          </select>
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} aria-label="Number of orders">
            <option value={50}>Last 50</option>
            <option value={100}>Last 100</option>
            <option value={200}>Last 200</option>
          </select>
        </div>

        {loading && !data ? (
          <Spinner />
        ) : rows.length === 0 ? (
          <Empty>{data?.length ? 'No orders match these filters.' : 'No orders yet.'}</Empty>
        ) : (
          <>
            <OrdersTable orders={rows} />
            <p className="table-foot">
              Showing {rows.length} of {data?.length ?? 0} most recent orders.
            </p>
          </>
        )}
      </Panel>
    </>
  );
}
