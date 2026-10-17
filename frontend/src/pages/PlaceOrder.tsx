import { useMemo, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { CreateOrderRequest, OrderItem } from '../api/types';
import { Badge, ErrorBanner, PageHeader, Panel, Spinner, useToast } from '../components/ui';
import { fmtMoney } from '../format';
import { usePolling } from '../hooks/usePolling';

/** Suggested unit prices for the seeded catalogue. Inventory does not own prices. */
const PRICE_HINTS: Record<string, number> = {
  'P-LAPTOP': 1200,
  'P-KEYBOARD': 80,
  'P-MOUSE': 25,
  'P-MONITOR': 400,
  'P-HEADPHONES': 90,
  'P-WEBCAM': 60,
  'P-OUT-OF-STOCK': 50,
  'P-BOOM': 10,
};

interface Scenario {
  key: string;
  title: string;
  expect: string;
  request: CreateOrderRequest;
}

/** The scenarios from docs/DEMO.md, one click each. */
const SCENARIOS: Scenario[] = [
  {
    key: 'happy',
    title: 'Happy path',
    expect: 'CONFIRMED / COMPLETED',
    request: { customerId: 'CUST-1001', simulateConfirmFailure: false, items: [{ productId: 'P-LAPTOP', quantity: 1, unitPrice: 1200 }] },
  },
  {
    key: 'reject',
    title: 'Out of stock',
    expect: 'CANCELLED / FAILED - nothing to compensate',
    request: { customerId: 'CUST-1002', simulateConfirmFailure: false, items: [{ productId: 'P-OUT-OF-STOCK', quantity: 1, unitPrice: 50 }] },
  },
  {
    key: 'decline',
    title: 'Payment declined',
    expect: 'CANCELLED / COMPENSATED - stock released',
    request: { customerId: 'CUST-1003', simulateConfirmFailure: false, items: [{ productId: 'P-MONITOR', quantity: 26, unitPrice: 400 }] },
  },
  {
    key: 'confirm',
    title: 'Confirm fails',
    expect: 'CANCELLED / COMPENSATED - refund, then release',
    request: { customerId: 'CUST-1004', simulateConfirmFailure: true, items: [{ productId: 'P-HEADPHONES', quantity: 2, unitPrice: 90 }] },
  },
  {
    key: 'dlt',
    title: 'Dead-letter',
    expect: 'Stuck in AWAITING_INVENTORY until replayed',
    request: { customerId: 'CUST-1005', simulateConfirmFailure: false, items: [{ productId: 'P-BOOM', quantity: 1, unitPrice: 10 }] },
  },
];

const DECLINE_ABOVE = 10_000;
const blankLine = (): OrderItem => ({ productId: '', quantity: 1, unitPrice: 0 });

export function PlaceOrder() {
  const navigate = useNavigate();
  const toast = useToast();
  const stock = usePolling(() => api.inventory.list(), 10_000);
  const sim = usePolling(() => api.inventory.simulation(), 0);

  const [customerId, setCustomerId] = useState('CUST-1001');
  const [lines, setLines] = useState<OrderItem[]>([{ productId: 'P-LAPTOP', quantity: 1, unitPrice: 1200 }]);
  const [simulateConfirmFailure, setSimulateConfirmFailure] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeScenario, setActiveScenario] = useState<string | null>('happy');

  const products = useMemo(() => [...(stock.data ?? [])].sort((a, b) => a.productId.localeCompare(b.productId)), [stock.data]);
  const byId = useMemo(() => Object.fromEntries(products.map((p) => [p.productId, p])), [products]);
  const total = lines.reduce((s, l) => s + (Number(l.quantity) || 0) * (Number(l.unitPrice) || 0), 0);
  const failingProduct = sim.data?.failingProductId || null;

  const warnings: string[] = [];
  if (total > DECLINE_ABOVE) warnings.push(`Total exceeds ${fmtMoney(DECLINE_ABOVE)} - payment-service will decline it and the saga will compensate.`);
  for (const l of lines) {
    const p = byId[l.productId];
    if (p && l.quantity > p.availableQty)
      warnings.push(`Only ${p.availableQty} × ${l.productId} available - inventory-service will reject the order.`);
    if (failingProduct && l.productId === failingProduct)
      warnings.push(`${l.productId} is the failure simulator's target - the reserve command will be retried, then dead-lettered.`);
  }
  if (customerId.trim() === 'CUST-BOOM') warnings.push('CUST-BOOM triggers a simulated payment gateway timeout - the payment command will be dead-lettered.');
  if (simulateConfirmFailure) warnings.push('Final confirmation will fail after payment - both compensations will run, refund first.');

  const update = (i: number, patch: Partial<OrderItem>) => {
    setActiveScenario(null);
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  };

  const applyScenario = (s: Scenario) => {
    setActiveScenario(s.key);
    setCustomerId(s.request.customerId);
    setSimulateConfirmFailure(s.request.simulateConfirmFailure);
    setLines(s.request.items.map((i) => ({ ...i })));
    setError(null);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const items = lines
      .filter((l) => l.productId.trim())
      .map((l) => ({ productId: l.productId.trim(), quantity: Number(l.quantity), unitPrice: Number(l.unitPrice) }));
    if (!customerId.trim()) return setError('Customer ID is required.');
    if (items.length === 0) return setError('Add at least one item.');
    setSubmitting(true);
    try {
      const created = await api.orders.create({ customerId: customerId.trim(), items, simulateConfirmFailure });
      toast(`Order ${created.orderId.slice(0, 8)} accepted (202). Tracking saga…`, 'good');
      navigate(`/orders/${created.orderId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHeader title="Place order" subtitle="Submits to order-service, which starts a saga across inventory and payment." />

      <Panel title="Scenarios" subtitle="Pre-filled from docs/DEMO.md. Pick one, then submit.">
        <div className="scenarios">
          {SCENARIOS.map((s) => (
            <button key={s.key} type="button" className={`scenario ${activeScenario === s.key ? 'active' : ''}`} onClick={() => applyScenario(s)}>
              <span className="scenario-title">{s.title}</span>
              <span className="scenario-expect">{s.expect}</span>
            </button>
          ))}
        </div>
        {activeScenario === 'dlt' && !failingProduct && (
          <p className="hint">
            The inventory failure simulator is currently <strong>disabled</strong>, so this order will go through. Re-arm it on the Inventory page.
          </p>
        )}
      </Panel>

      <form onSubmit={submit} className="grid-order">
        <Panel title="Order">
          <ErrorBanner error={error} />
          <label className="field">
            <span>Customer ID</span>
            <input
              value={customerId}
              onChange={(e) => {
                setActiveScenario(null);
                setCustomerId(e.target.value);
              }}
              placeholder="CUST-1001"
              required
            />
          </label>

          <div className="lines">
            <div className="lines-head">
              <span>Product</span>
              <span>Qty</span>
              <span>Unit price</span>
              <span className="num">Subtotal</span>
              <span />
            </div>
            {lines.map((l, i) => {
              const p = byId[l.productId];
              return (
                <div className="line" key={i}>
                  <div>
                    <input
                      list="products"
                      value={l.productId}
                      placeholder="P-LAPTOP"
                      aria-label="Product"
                      onChange={(e) => {
                        const productId = e.target.value;
                        update(i, { productId, ...(PRICE_HINTS[productId] && !l.unitPrice ? { unitPrice: PRICE_HINTS[productId] } : {}) });
                      }}
                      onBlur={() => {
                        if (PRICE_HINTS[l.productId] && !l.unitPrice) update(i, { unitPrice: PRICE_HINTS[l.productId] });
                      }}
                    />
                    <div className="line-hint">
                      {p ? `${p.availableQty} available · ${p.reservedQty} reserved` : l.productId ? 'Not in inventory' : ' '}
                    </div>
                  </div>
                  <input
                    type="number"
                    min={1}
                    value={l.quantity}
                    aria-label="Quantity"
                    onChange={(e) => update(i, { quantity: Number(e.target.value) })}
                  />
                  <input
                    type="number"
                    min={0}
                    step="0.01"
                    value={l.unitPrice}
                    aria-label="Unit price"
                    onChange={(e) => update(i, { unitPrice: Number(e.target.value) })}
                  />
                  <span className="num line-sub">{fmtMoney((Number(l.quantity) || 0) * (Number(l.unitPrice) || 0))}</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    aria-label="Remove line"
                    disabled={lines.length === 1}
                    onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}
                  >
                    ✕
                  </button>
                </div>
              );
            })}
            <datalist id="products">
              {products.map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.availableQty} available
                </option>
              ))}
            </datalist>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLines((ls) => [...ls, blankLine()])}>
              + Add line
            </button>
          </div>

          <label className="check">
            <input
              type="checkbox"
              checked={simulateConfirmFailure}
              onChange={(e) => {
                setActiveScenario(null);
                setSimulateConfirmFailure(e.target.checked);
              }}
            />
            <span>
              <strong>Simulate confirm failure</strong>
              <span className="muted"> - fail the saga's final local step so the full compensation chain runs.</span>
            </span>
          </label>
        </Panel>

        <div className="order-side">
          <Panel title="Summary">
            <div className="summary-row">
              <span>Lines</span>
              <span>{lines.filter((l) => l.productId).length}</span>
            </div>
            <div className="summary-row">
              <span>Units</span>
              <span>{lines.reduce((n, l) => n + (Number(l.quantity) || 0), 0)}</span>
            </div>
            <div className="summary-row summary-total">
              <span>Total</span>
              <span>{fmtMoney(total)}</span>
            </div>

            {warnings.length > 0 && (
              <ul className="predictions">
                {warnings.map((w) => (
                  <li key={w}>
                    <Badge tone="warning">Expect</Badge> {w}
                  </li>
                ))}
              </ul>
            )}

            <button type="submit" className="btn btn-primary btn-block" disabled={submitting}>
              {submitting ? <Spinner /> : 'Place order'}
            </button>
            <p className="hint">The API answers 202 Accepted immediately; you will be taken to the order to watch the saga run.</p>
          </Panel>

          <Panel title="Catalogue" subtitle="Live stock from inventory-service.">
            <ErrorBanner error={stock.error} onRetry={stock.refresh} />
            <ul className="catalogue">
              {products.map((p) => (
                <li key={p.productId}>
                  <button
                    type="button"
                    className="catalogue-item"
                    onClick={() => {
                      setActiveScenario(null);
                      setLines((ls) => {
                        const existing = ls.findIndex((l) => l.productId === p.productId);
                        if (existing >= 0) return ls.map((l, j) => (j === existing ? { ...l, quantity: l.quantity + 1 } : l));
                        const fresh = { productId: p.productId, quantity: 1, unitPrice: PRICE_HINTS[p.productId] ?? 10 };
                        return ls.length === 1 && !ls[0].productId ? [fresh] : [...ls, fresh];
                      });
                    }}
                  >
                    <span className="mono-sm">{p.productId}</span>
                    <span className={p.availableQty === 0 ? 'stock-out' : 'muted'}>{p.availableQty === 0 ? 'Out of stock' : `${p.availableQty} avail.`}</span>
                    {p.productId === failingProduct && <Badge tone="serious">fails</Badge>}
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      </form>
    </>
  );
}
