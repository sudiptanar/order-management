import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Reservation, Simulation, Stock } from '../api/types';
import { Badge, Empty, ErrorBanner, LiveIndicator, PageHeader, Panel, Spinner, useToast } from '../components/ui';
import { humanize } from '../format';
import { usePolling, type Polled } from '../hooks/usePolling';

const POLL = 4000;

function StockRow({ item, failing, onSaved }: { item: Stock; failing: boolean; onSaved: () => void }) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(String(item.availableQty));
  const [saving, setSaving] = useState(false);
  const availPct = item.totalQty ? (item.availableQty / item.totalQty) * 100 : 0;
  const resPct = item.totalQty ? (item.reservedQty / item.totalQty) * 100 : 0;

  const save = async () => {
    const qty = Number(value);
    if (!Number.isInteger(qty) || qty < 0) return toast('Stock must be a whole number ≥ 0', 'critical');
    setSaving(true);
    try {
      await api.inventory.setStock(item.productId, qty);
      toast(`${item.productId} available stock set to ${qty}`, 'good');
      setEditing(false);
      onSaved();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'critical');
    } finally {
      setSaving(false);
    }
  };

  return (
    <tr>
      <td>
        <span className="mono-sm">{item.productId}</span> {failing && <Badge tone="serious">failure target</Badge>}
      </td>
      <td className="num">
        {editing ? (
          <span className="inline-edit">
            <input
              type="number"
              min={0}
              value={value}
              autoFocus
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') save();
                if (e.key === 'Escape') setEditing(false);
              }}
              aria-label={`Available stock for ${item.productId}`}
            />
            <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>
              Save
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </span>
        ) : (
          item.availableQty
        )}
      </td>
      <td className="num">{item.reservedQty}</td>
      <td className="num">{item.totalQty}</td>
      <td className="stock-bar-cell">
        <div
          className="stock-bar"
          title={`${item.availableQty} available, ${item.reservedQty} reserved by in-flight sagas`}
          aria-label={`${item.availableQty} available, ${item.reservedQty} reserved`}
        >
          {availPct > 0 && <span className="stock-avail" style={{ width: `${availPct}%` }} />}
          {resPct > 0 && <span className="stock-res" style={{ width: `${resPct}%` }} />}
        </div>
      </td>
      <td className="num">
        {!editing && (
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              setValue(String(item.availableQty));
              setEditing(true);
            }}
          >
            Set stock
          </button>
        )}
      </td>
    </tr>
  );
}

function SimulatorPanel({ products, sim }: { products: Stock[]; sim: Polled<Simulation> }) {
  const toast = useToast();
  const [target, setTarget] = useState('');
  const current = sim.data?.failingProductId || '';

  const apply = async (value: string) => {
    try {
      await api.inventory.setSimulation(value);
      toast(value ? `Failure simulator now targets ${value}` : 'Failure simulator disabled', value ? 'warning' : 'good');
      setTarget('');
      sim.refresh();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'critical');
    }
  };

  return (
    <Panel
      title="Failure simulator"
      subtitle="Test hook. Reserving the target product throws a retryable error: retried 3×, then dead-lettered."
    >
      <ErrorBanner error={sim.error} onRetry={sim.refresh} />
      <div className="sim-state">
        <span className="stat-label">Current target</span>
        {current ? <Badge tone="serious">{current}</Badge> : <Badge tone="good">Disabled</Badge>}
      </div>
      <div className="sim-actions">
        <button className="btn btn-secondary" disabled={!current} onClick={() => apply('')}>
          Disable (fix the cause)
        </button>
        <button className="btn btn-ghost" disabled={current === 'P-BOOM'} onClick={() => apply('P-BOOM')}>
          Re-arm P-BOOM
        </button>
      </div>
      <form
        className="inline-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (target.trim()) apply(target.trim());
        }}
      >
        <input list="sim-products" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="Retarget to product ID" aria-label="Target product" />
        <datalist id="sim-products">
          {products.map((p) => (
            <option key={p.productId} value={p.productId} />
          ))}
        </datalist>
        <button className="btn btn-ghost" type="submit" disabled={!target.trim()}>
          Retarget
        </button>
      </form>
      <p className="hint">
        The dead-letter workflow is: <strong>fix the cause, then replay</strong> on <Link to="/operations">Operations</Link>. Replaying first just
        refills the DLT.
      </p>
    </Panel>
  );
}

function ReservationLookup() {
  const [orderId, setOrderId] = useState('');
  const [result, setResult] = useState<Reservation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const lookup = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setResult(await api.inventory.reservations(orderId.trim()));
    } catch (err) {
      setResult(null);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Reservations by order" subtitle="What is held for an order, and whether it was released.">
      <form className="inline-form" onSubmit={lookup}>
        <input value={orderId} onChange={(e) => setOrderId(e.target.value)} placeholder="Order ID (UUID)" aria-label="Order ID" required />
        <button className="btn btn-secondary" type="submit" disabled={busy}>
          Look up
        </button>
      </form>
      <ErrorBanner error={error} />
      {result &&
        (result.length === 0 ? (
          <Empty>No reservations for this order.</Empty>
        ) : (
          <table className="table table-tight">
            <thead>
              <tr>
                <th>Product</th>
                <th className="num">Qty</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {result.map((r) => (
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
        ))}
      {result && result.length > 0 && (
        <p className="hint">
          <Link to={`/orders/${orderId.trim()}`}>Open order trace →</Link>
        </p>
      )}
    </Panel>
  );
}

export function Inventory() {
  const toast = useToast();
  const stock = usePolling(() => api.inventory.list(), POLL);
  const sim = usePolling(() => api.inventory.simulation(), POLL);
  const [newId, setNewId] = useState('');
  const [newQty, setNewQty] = useState('10');
  const items = [...(stock.data ?? [])].sort((a, b) => a.productId.localeCompare(b.productId));
  const failing = sim.data?.failingProductId || '';

  const addProduct = async (e: FormEvent) => {
    e.preventDefault();
    const qty = Number(newQty);
    if (!newId.trim() || !Number.isInteger(qty) || qty < 0) return;
    try {
      await api.inventory.setStock(newId.trim(), qty);
      toast(`${newId.trim()} saved with ${qty} available`, 'good');
      setNewId('');
      stock.refresh();
    } catch (err) {
      toast(err instanceof Error ? err.message : String(err), 'critical');
    }
  };

  return (
    <>
      <PageHeader
        title="Inventory"
        subtitle="inventory-service · stock is split into available (can be promised) and reserved (held by in-flight sagas)."
        actions={<LiveIndicator lastUpdated={stock.lastUpdated} intervalMs={POLL} />}
      />
      <ErrorBanner error={stock.error} onRetry={stock.refresh} />

      <Panel
        title="Stock levels"
        actions={
          <span className="legend">
            <span className="legend-swatch stock-avail" /> Available
            <span className="legend-swatch stock-res" /> Reserved
          </span>
        }
      >
        {stock.loading && !stock.data ? (
          <Spinner />
        ) : items.length === 0 ? (
          <Empty>No products.</Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Available</th>
                  <th className="num">Reserved</th>
                  <th className="num">Total</th>
                  <th>Split</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((i) => (
                  <StockRow key={i.productId} item={i} failing={i.productId === failing} onSaved={stock.refresh} />
                ))}
              </tbody>
            </table>
          </div>
        )}
        <form className="inline-form add-product" onSubmit={addProduct}>
          <input value={newId} onChange={(e) => setNewId(e.target.value)} placeholder="New product ID, e.g. P-TABLET" aria-label="New product ID" />
          <input type="number" min={0} value={newQty} onChange={(e) => setNewQty(e.target.value)} aria-label="Initial available quantity" />
          <button className="btn btn-secondary" type="submit" disabled={!newId.trim()}>
            Add / set product
          </button>
        </form>
      </Panel>

      <div className="grid-2">
        <SimulatorPanel products={items} sim={sim} />
        <ReservationLookup />
      </div>
    </>
  );
}
