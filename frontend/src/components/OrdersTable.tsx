import { useNavigate } from 'react-router-dom';
import type { Order } from '../api/types';
import { fmtMoney, timeAgo } from '../format';
import { looksStuck, ORDER_STATUS_TONE, SAGA_STATE_META } from '../saga';
import { Badge, CopyId, StatusBadge } from './ui';

export function OrdersTable({ orders, compact = false }: { orders: Order[]; compact?: boolean }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Order</th>
            <th>Customer</th>
            {!compact && <th>Items</th>}
            <th className="num">Total</th>
            <th>Status</th>
            <th>Saga</th>
            {!compact && <th>Waiting on</th>}
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => {
            const saga = o.sagaState ? SAGA_STATE_META[o.sagaState] : null;
            const stuck = looksStuck(o);
            return (
              <tr key={o.orderId} className="row-link" onClick={() => navigate(`/orders/${o.orderId}`)}>
                <td>
                  <CopyId id={o.orderId} />
                </td>
                <td>{o.customerId}</td>
                {!compact && (
                  <td className="muted ellipsis" title={o.items.map((i) => `${i.quantity} × ${i.productId}`).join(', ')}>
                    {o.items.map((i) => `${i.quantity}× ${i.productId}`).join(', ')}
                  </td>
                )}
                <td className="num">{fmtMoney(o.totalAmount)}</td>
                <td>
                  <StatusBadge value={o.status} tones={ORDER_STATUS_TONE} />
                </td>
                <td>
                  {saga ? (
                    <span className="saga-cell">
                      <Badge tone={saga.tone}>{saga.label}</Badge>
                      {stuck && (
                        <Badge tone="warning" title="In flight for over 30s - a command may be dead-lettered">
                          Stuck?
                        </Badge>
                      )}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                {!compact && <td className="muted mono-sm">{o.sagaStep ?? '—'}</td>}
                <td className="muted nowrap" title={o.updatedAt}>
                  {timeAgo(o.updatedAt)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
