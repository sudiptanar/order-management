import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Notification, Order, Payment, Reservation } from '../api/types';
import { SagaDiagram } from '../components/SagaDiagram';
import { Badge, CopyId, Empty, ErrorBanner, LiveIndicator, Mono, PageHeader, Panel, Spinner, StatusBadge } from '../components/ui';
import { fmtMoney, fmtTime, humanize, timeAgo } from '../format';
import { usePolling } from '../hooks/usePolling';
import { buildTimeline, isInFlight, looksStuck, ORDER_STATUS_TONE, PAYMENT_STATUS_TONE, SAGA_STATE_META, sagaPath, type StepStatus } from '../saga';

const FAST = 1500;
const SLOW = 8000;

interface Trace {
  order: Order;
  reservations: Reservation[] | undefined;
  payment: Payment | null | undefined;
  notifications: Notification[] | undefined;
  errors: Record<string, string>;
}

/**
 * Pulls one order's state from all four services. Each participant is queried independently,
 * so one being down degrades its own panel rather than the whole view.
 */
async function loadTrace(id: string): Promise<Trace> {
  const errors: Record<string, string> = {};
  const soft = <T,>(name: string, p: Promise<T>) =>
    p.catch((e) => {
      errors[name] = e instanceof Error ? e.message : String(e);
      return undefined;
    });
  const [order, reservations, payment, notifications] = await Promise.all([
    api.orders.get(id),
    soft('inventory', api.inventory.reservations(id)),
    soft('payment', api.payments.forOrder(id)),
    soft('notification', api.notifications.forOrder(id)),
  ]);
  return { order, reservations, payment, notifications, errors };
}

const STEP_ICON: Record<StepStatus, string> = { done: '✓', active: '', failed: '✕', pending: '', skipped: '–' };

export function OrderDetail() {
  const { orderId = '' } = useParams();
  const [interval, setIntervalMs] = useState(FAST);
  const { data, error, loading, refresh, lastUpdated } = usePolling(
    async () => {
      const t = await loadTrace(orderId);
      // Poll fast while the saga moves, then back off once it has settled.
      const settled = !isInFlight(t.order) && (t.notifications?.length ?? 0) > 1;
      setIntervalMs(settled ? SLOW : FAST);
      return t;
    },
    interval,
    [orderId],
  );

  if (loading && !data) return <Spinner />;
  if (!data)
    return (
      <>
        <PageHeader title="Order not found" />
        <ErrorBanner error={error} onRetry={refresh} />
        <Link to="/orders">← Back to orders</Link>
      </>
    );

  const { order, reservations, payment, notifications, errors } = data;
  const saga = order.sagaState ? SAGA_STATE_META[order.sagaState] : null;
  const live = isInFlight(order);
  const path = order.sagaState ? sagaPath(order.sagaState, order.sagaStep, payment) : [];
  const timeline = buildTimeline(order, reservations, payment, notifications);
  const stuck = looksStuck(order);

  return (
    <>
      <div className="crumbs">
        <Link to="/orders">Orders</Link> / <span>{order.orderId.slice(0, 8)}</span>
      </div>
      <PageHeader
        title={`Order ${order.orderId.slice(0, 8)}`}
        subtitle={
          <>
            <Mono>{order.orderId}</Mono> · {order.customerId} · placed {fmtTime(order.createdAt)}
          </>
        }
        actions={<LiveIndicator lastUpdated={lastUpdated} intervalMs={interval} />}
      />
      <ErrorBanner error={error} onRetry={refresh} />

      <div className="hero">
        <div className="hero-item">
          <div className="stat-label">Order status</div>
          <StatusBadge value={order.status} tones={ORDER_STATUS_TONE} />
        </div>
        <div className="hero-item">
          <div className="stat-label">Saga state</div>
          {saga ? <Badge tone={saga.tone}>{saga.label}</Badge> : '—'}
        </div>
        <div className="hero-item">
          <div className="stat-label">Outstanding step</div>
          <span className="mono-sm">{order.sagaStep ?? 'none'}</span>
        </div>
        <div className="hero-item">
          <div className="stat-label">Total</div>
          <span className="hero-value">{fmtMoney(order.totalAmount)}</span>
        </div>
        <div className="hero-item">
          <div className="stat-label">Last change</div>
          <span title={order.updatedAt}>{timeAgo(order.updatedAt)}</span>
        </div>
      </div>

      {order.failureReason && (
        <div className="banner banner-serious">
          <span className="badge-icon">↺</span>
          <span>
            <strong>Reason:</strong> {order.failureReason}
          </span>
        </div>
      )}
      {stuck && (
        <div className="banner banner-warning">
          <span className="badge-icon">!</span>
          <span>
            No progress for {timeAgo(order.updatedAt).replace(' ago', '')}. The outstanding <Mono>{order.sagaStep}</Mono> command was probably retried and
            dead-lettered. Fix the cause, then replay it.
          </span>
          <Link className="btn btn-ghost btn-sm" to="/operations">
            Open operations
          </Link>
        </div>
      )}
      {order.sagaState === 'COMPENSATION_FAILED' && (
        <div className="banner banner-critical">
          <span className="badge-icon">✕</span>
          <span>A compensation failed. There is no automatic way forward - money or stock is stranded and a person has to resolve it.</span>
        </div>
      )}

      <div className="grid-detail">
        <Panel title="Saga state machine" subtitle="Highlighted: the path this order's saga has taken.">
          {path.length ? <SagaDiagram path={path} live={live} /> : <Empty>No saga instance.</Empty>}
        </Panel>

        <Panel title="Cross-service trace" subtitle="Each step as reported by the service that owns it.">
          <ol className="timeline">
            {timeline.map((s) => (
              <li key={s.key} className={`tl-step tl-${s.status} ${s.compensation ? 'tl-comp' : ''}`}>
                <span className="tl-marker" aria-hidden>
                  {STEP_ICON[s.status]}
                </span>
                <div className="tl-body">
                  <div className="tl-title">
                    {s.title}
                    {s.compensation && <span className="tl-tag">compensation</span>}
                    <span className="tl-status">{humanize(s.status === 'active' ? 'in progress' : s.status)}</span>
                  </div>
                  <div className="tl-service">{s.service}</div>
                  <div className="tl-detail">{s.detail}</div>
                </div>
              </li>
            ))}
          </ol>
        </Panel>
      </div>

      <div className="grid-3">
        <Panel title="Items" subtitle="order-service">
          <table className="table table-tight">
            <thead>
              <tr>
                <th>Product</th>
                <th className="num">Qty</th>
                <th className="num">Price</th>
                <th className="num">Subtotal</th>
              </tr>
            </thead>
            <tbody>
              {order.items.map((i) => (
                <tr key={i.productId}>
                  <td className="mono-sm">{i.productId}</td>
                  <td className="num">{i.quantity}</td>
                  <td className="num">{fmtMoney(i.unitPrice)}</td>
                  <td className="num">{fmtMoney(i.quantity * i.unitPrice)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <dl className="kv">
            <dt>Saga ID</dt>
            <dd>{order.sagaId ? <CopyId id={order.sagaId} /> : '—'}</dd>
            <dt>Created</dt>
            <dd>{fmtTime(order.createdAt)}</dd>
            <dt>Updated</dt>
            <dd>{fmtTime(order.updatedAt)}</dd>
          </dl>
        </Panel>

        <Panel title="Stock reservations" subtitle="inventory-service">
          <ErrorBanner error={errors.inventory} />
          {reservations && reservations.length > 0 ? (
            <table className="table table-tight">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Qty</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {reservations.map((r) => (
                  <tr key={r.id}>
                    <td className="mono-sm">{r.productId}</td>
                    <td className="num">{r.quantity}</td>
                    <td>
                      <Badge tone={r.status === 'ACTIVE' ? 'good' : 'warning'}>{humanize(r.status)}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            !errors.inventory && <Empty>Nothing held for this order.</Empty>
          )}
        </Panel>

        <Panel title="Payment" subtitle="payment-service">
          <ErrorBanner error={errors.payment} />
          {payment ? (
            <dl className="kv">
              <dt>Status</dt>
              <dd>
                <StatusBadge value={payment.status} tones={PAYMENT_STATUS_TONE} />
              </dd>
              <dt>Amount</dt>
              <dd>{fmtMoney(payment.amount)}</dd>
              <dt>Auth ref</dt>
              <dd className="mono-sm">{payment.authorizationRef ?? '—'}</dd>
              {payment.failureReason && (
                <>
                  <dt>Reason</dt>
                  <dd>{payment.failureReason}</dd>
                </>
              )}
              <dt>Updated</dt>
              <dd>{fmtTime(payment.updatedAt)}</dd>
            </dl>
          ) : (
            !errors.payment && <Empty>No payment taken.</Empty>
          )}
        </Panel>
      </div>

      <Panel title="Notifications" subtitle="notification-service - consumed from order.events">
        <ErrorBanner error={errors.notification} />
        {notifications && notifications.length > 0 ? (
          <ul className="notes">
            {notifications.map((n) => (
              <li key={n.id} className="note">
                <div className="note-head">
                  <Badge tone={n.eventType === 'ORDER_CONFIRMED' ? 'good' : n.eventType === 'ORDER_CANCELLED' ? 'critical' : 'info'}>
                    {humanize(n.eventType)}
                  </Badge>
                  <span className="muted">
                    {n.channel} · {fmtTime(n.createdAt)}
                  </span>
                </div>
                <div className="note-subject">{n.subject}</div>
                <pre className="note-body">{n.body}</pre>
              </li>
            ))}
          </ul>
        ) : (
          !errors.notification && <Empty>No notifications yet.</Empty>
        )}
      </Panel>
    </>
  );
}
