import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { DltDepth, Order, OutboxHealth } from '../api/types';
import { OrdersTable } from '../components/OrdersTable';
import { SagaSummaryChart } from '../components/SagaSummaryChart';
import { ServiceHealthList } from '../components/ServiceHealth';
import { Badge, Empty, ErrorBanner, LiveIndicator, PageHeader, Panel, Spinner } from '../components/ui';
import { fmtMoney } from '../format';
import { usePolling } from '../hooks/usePolling';
import { isInFlight, looksStuck } from '../saga';

const POLL = 3000;

interface Snapshot {
  orders: Order[];
  summary: Record<string, number>;
  dlt: Record<string, DltDepth> | null;
  outbox: OutboxHealth | null;
}

async function load(): Promise<Snapshot> {
  // The DLT and outbox views are nice-to-have; a slow broker should not blank the dashboard.
  const [orders, summary, dlt, outbox] = await Promise.all([
    api.orders.list(200),
    api.orders.sagaSummary(),
    api.ops.dlt().catch(() => null),
    api.ops.outbox().catch(() => null),
  ]);
  return { orders, summary, dlt, outbox };
}

function Stat({ label, value, hint, tone }: { label: string; value: string | number; hint?: string; tone?: 'good' | 'warning' | 'critical' }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">
        {value}
        {tone && <span className={`stat-dot tone-${tone}`} aria-hidden />}
      </div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  );
}

export function Dashboard() {
  const { data, error, loading, refresh, lastUpdated } = usePolling(load, POLL);

  const orders = data?.orders ?? [];
  const confirmed = orders.filter((o) => o.status === 'CONFIRMED');
  const cancelled = orders.filter((o) => o.status === 'CANCELLED');
  const inFlight = orders.filter(isInFlight);
  const stuck = orders.filter(looksStuck);
  const needsHuman = data?.summary.COMPENSATION_FAILED ?? 0;
  const dltPending = data?.dlt ? Object.values(data.dlt).reduce((n, d) => n + d.pendingReplay, 0) : null;
  const outboxPending = typeof data?.outbox?.pending === 'number' ? data.outbox.pending : null;
  const outboxFailed = typeof data?.outbox?.failed === 'number' ? data.outbox.failed : null;
  const finished = confirmed.length + cancelled.length;
  const successRate = finished ? Math.round((confirmed.length / finished) * 100) : null;
  const revenue = confirmed.reduce((s, o) => s + Number(o.totalAmount), 0);

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle="Orders, sagas and messaging health across all four services."
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

      {(needsHuman > 0 || (dltPending ?? 0) > 0 || stuck.length > 0) && (
        <div className="alerts">
          {needsHuman > 0 && (
            <div className="banner banner-critical">
              <span className="badge-icon">✕</span>
              <span>
                <strong>{needsHuman}</strong> saga{needsHuman > 1 ? 's' : ''} in COMPENSATION_FAILED. Money or stock is stranded and needs manual
                intervention.
              </span>
              <Link className="btn btn-ghost btn-sm" to="/orders?saga=COMPENSATION_FAILED">
                View
              </Link>
            </div>
          )}
          {(dltPending ?? 0) > 0 && (
            <div className="banner banner-warning">
              <span className="badge-icon">!</span>
              <span>
                <strong>{dltPending}</strong> dead-lettered message{dltPending! > 1 ? 's' : ''} awaiting replay. Fix the cause, then replay.
              </span>
              <Link className="btn btn-ghost btn-sm" to="/operations">
                Open operations
              </Link>
            </div>
          )}
          {stuck.length > 0 && (
            <div className="banner banner-warning">
              <span className="badge-icon">!</span>
              <span>
                <strong>{stuck.length}</strong> saga{stuck.length > 1 ? 's have' : ' has'} had no progress for over 30s.
              </span>
              <Link className="btn btn-ghost btn-sm" to="/orders?filter=stuck">
                View
              </Link>
            </div>
          )}
        </div>
      )}

      <div className="stats">
        <Stat label="Orders (last 200)" value={orders.length} hint={`${inFlight.length} in flight`} />
        <Stat label="Confirmed" value={confirmed.length} hint={`${fmtMoney(revenue)} captured`} />
        <Stat label="Cancelled" value={cancelled.length} hint={successRate == null ? 'No finished orders yet' : `${successRate}% success rate`} />
        <Stat
          label="DLT pending replay"
          value={dltPending ?? '—'}
          tone={dltPending == null ? undefined : dltPending > 0 ? 'warning' : 'good'}
          hint={dltPending == null ? 'Broker unavailable' : 'Across 6 dead-letter topics'}
        />
        <Stat
          label="Outbox pending"
          value={outboxPending ?? '—'}
          tone={outboxFailed ? 'critical' : outboxPending == null ? undefined : 'good'}
          hint={outboxFailed ? `${outboxFailed} failed to publish` : 'Waiting to be published'}
        />
      </div>

      <div className="grid-2">
        <Panel title="Sagas by state" subtitle="All sagas in order-service. Click a state to filter.">
          {data ? <SagaSummaryChart summary={data.summary} /> : loading ? <Spinner /> : null}
        </Panel>
        <Panel title="Services" subtitle="Spring Boot actuator health, checked every 10s.">
          <ServiceHealthList />
        </Panel>
      </div>

      <Panel
        title="Recent orders"
        actions={
          <Link to="/orders" className="btn btn-ghost btn-sm">
            View all →
          </Link>
        }
      >
        {loading && !data ? (
          <Spinner />
        ) : orders.length === 0 ? (
          <Empty>
            No orders yet. <Link to="/orders/new">Place the first one</Link>.
          </Empty>
        ) : (
          <OrdersTable orders={orders.slice(0, 10)} compact />
        )}
      </Panel>

      {inFlight.length > 0 && (
        <p className="footnote">
          <Badge tone="info">{inFlight.length} in flight</Badge> Sagas are asynchronous: the API accepts an order with 202 and the outcome arrives as
          participants reply.
        </p>
      )}
    </>
  );
}
