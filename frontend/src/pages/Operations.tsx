import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { DltRecord, OutboxFailure } from '../api/types';
import { SagaSummaryChart } from '../components/SagaSummaryChart';
import { Badge, Empty, ErrorBanner, LiveIndicator, Mono, PageHeader, Panel, Spinner, useToast } from '../components/ui';
import { fmtTime, humanize } from '../format';
import { usePolling } from '../hooks/usePolling';

const POLL = 5000;

const originOf = (dlt: string) => dlt.replace(/\.DLT$/, '');

/** Tries to find the order a dead-lettered payload refers to, so the operator can jump to it. */
function orderIdOf(r: DltRecord): string | null {
  if (r.key && /^[0-9a-f-]{36}$/i.test(r.key)) return r.key;
  try {
    return JSON.parse(r.payload ?? '')?.orderId ?? null;
  } catch {
    return null;
  }
}

function prettyJson(s: string | null) {
  if (!s) return '';
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

function DltViewer({ origin, onReplayed }: { origin: string; onReplayed: () => void }) {
  const toast = useToast();
  const peek = usePolling(() => api.ops.peek(origin, 50), 0, [origin]);
  const [max, setMax] = useState(20);
  const [replaying, setReplaying] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

  const replay = async () => {
    setReplaying(true);
    try {
      const r = await api.ops.replay(origin, max);
      toast(`${r.note}: ${r.replayed} record${r.replayed === 1 ? '' : 's'} → ${r.toTopic}`, r.replayed ? 'good' : 'neutral');
      onReplayed();
      peek.refresh();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'critical');
    } finally {
      setReplaying(false);
    }
  };

  return (
    <Panel
      title={
        <>
          Dead letters · <Mono>{origin}.DLT</Mono>
        </>
      }
      subtitle="Read without consuming - peeking never affects replay."
      actions={
        <>
          <label className="inline-label">
            Max
            <input type="number" min={1} max={200} value={max} onChange={(e) => setMax(Number(e.target.value))} aria-label="Max records to replay" />
          </label>
          <button className="btn btn-ghost btn-sm" onClick={peek.refresh}>
            Refresh
          </button>
          <button className="btn btn-primary btn-sm" onClick={replay} disabled={replaying}>
            {replaying ? <Spinner /> : `Replay to ${origin}`}
          </button>
        </>
      }
    >
      <ErrorBanner error={peek.error} onRetry={peek.refresh} />
      {peek.loading && !peek.data ? (
        <Spinner />
      ) : !peek.data?.length ? (
        <Empty>This dead-letter topic is empty.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Partition / offset</th>
                <th>Order</th>
                <th>Exception</th>
                <th>When</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {peek.data.map((r) => {
                const id = orderIdOf(r);
                const isOpen = open === r.offset * 1000 + r.partition;
                return (
                  <Fragment key={`${r.partition}-${r.offset}`}>
                    <tr>
                      <td className="mono-sm">
                        p{r.partition} @ {r.offset}
                      </td>
                      <td>{id ? <Link to={`/orders/${id}`} className="mono-sm">{id.slice(0, 8)}</Link> : '—'}</td>
                      <td>
                        <div className="exc-type">{r.exceptionType?.split('.').pop() ?? '—'}</div>
                        <div className="muted exc-msg">{r.exceptionMessage}</div>
                      </td>
                      <td className="muted nowrap">{fmtTime(r.timestamp)}</td>
                      <td className="num">
                        <button className="btn btn-ghost btn-sm" onClick={() => setOpen(isOpen ? null : r.offset * 1000 + r.partition)}>
                          {isOpen ? 'Hide' : 'Payload'}
                        </button>
                      </td>
                    </tr>
                    {isOpen && (
                      <tr className="payload-row">
                        <td colSpan={5}>
                          <pre className="code">{prettyJson(r.payload)}</pre>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

export function Operations() {
  const dlt = usePolling(() => api.ops.dlt(), POLL);
  const outbox = usePolling(() => api.ops.outbox(), POLL);
  const summary = usePolling(() => api.orders.sagaSummary(), POLL);
  const sim = usePolling(() => api.inventory.simulation(), POLL);
  const toast = useToast();
  const [selected, setSelected] = useState<string | null>(null);

  const entries = Object.entries(dlt.data ?? {});
  const outboxCounts = Object.entries(outbox.data ?? {}).filter(([, v]) => typeof v === 'number') as [string, number][];
  const failures = (outbox.data?.recentFailures ?? []) as OutboxFailure[];
  const failing = sim.data?.failingProductId || '';

  return (
    <>
      <PageHeader
        title="Operations"
        subtitle="Dead-letter topics, replay, outbox health. Replay is safe because every consumer is idempotent."
        actions={<LiveIndicator lastUpdated={dlt.lastUpdated} intervalMs={POLL} />}
      />

      <Panel title="Recovering a dead-lettered message" className="workflow">
        <ol className="steps">
          <li>
            <strong>Find it.</strong> A topic below shows <em>pending replay</em> &gt; 0. Open it to read the exception.
          </li>
          <li>
            <strong>Fix the cause.</strong> For the demo, that means disabling the failure simulator:{' '}
            {failing ? (
              <>
                currently targeting <Badge tone="serious">{failing}</Badge>{' '}
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={async () => {
                    try {
                      await api.inventory.setSimulation('');
                      toast('Failure simulator disabled', 'good');
                      sim.refresh();
                    } catch (e) {
                      toast(e instanceof Error ? e.message : String(e), 'critical');
                    }
                  }}
                >
                  Disable now
                </button>
              </>
            ) : (
              <Badge tone="good">Simulator disabled</Badge>
            )}
          </li>
          <li>
            <strong>Replay.</strong> Messages go back to their origin topic; the stuck saga resumes. Replaying again reports nothing left.
          </li>
        </ol>
      </Panel>

      <Panel
        title="Dead-letter topics"
        subtitle={
          <>
            <em>Total</em> never decreases - Kafka is a log. <em>Pending replay</em> is measured against the replay group's offsets and is the number
            to alert on.
          </>
        }
      >
        <ErrorBanner error={dlt.error} onRetry={dlt.refresh} />
        {dlt.loading && !dlt.data ? (
          <Spinner />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Dead-letter topic</th>
                  <th className="num">Total written</th>
                  <th className="num">Pending replay</th>
                  <th>Health</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {entries.map(([topic, d]) => {
                  const origin = originOf(topic);
                  return (
                    <tr key={topic} className={selected === origin ? 'row-selected' : ''}>
                      <td className="mono-sm">{topic}</td>
                      <td className="num">{d.total}</td>
                      <td className="num">
                        <strong>{d.pendingReplay}</strong>
                      </td>
                      <td>{d.pendingReplay > 0 ? <Badge tone="warning">Needs replay</Badge> : <Badge tone="good">Drained</Badge>}</td>
                      <td className="num">
                        <button className="btn btn-ghost btn-sm" onClick={() => setSelected(selected === origin ? null : origin)}>
                          {selected === origin ? 'Close' : 'Inspect'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {selected && <DltViewer key={selected} origin={selected} onReplayed={dlt.refresh} />}

      <div className="grid-2">
        <Panel title="Transactional outbox" subtitle="order-service. A growing pending count means the publisher is not keeping up.">
          <ErrorBanner error={outbox.error} onRetry={outbox.refresh} />
          <div className="stats stats-inline">
            {outboxCounts.map(([k, v]) => (
              <div className="stat" key={k}>
                <div className="stat-label">{humanize(k)}</div>
                <div className="stat-value">
                  {v}
                  {k === 'failed' && v > 0 && <span className="stat-dot tone-critical" aria-hidden />}
                </div>
              </div>
            ))}
          </div>
          {failures.length > 0 ? (
            <table className="table table-tight">
              <thead>
                <tr>
                  <th>Topic</th>
                  <th className="num">Attempts</th>
                  <th>Last error</th>
                </tr>
              </thead>
              <tbody>
                {failures.map((f) => (
                  <tr key={f.id}>
                    <td className="mono-sm">{f.topic}</td>
                    <td className="num">{f.attempts}</td>
                    <td className="muted">{f.lastError}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            outbox.data && <p className="hint">No recent publish failures.</p>
          )}
        </Panel>
        <Panel title="Sagas by state">
          <ErrorBanner error={summary.error} onRetry={summary.refresh} />
          {summary.data ? <SagaSummaryChart summary={summary.data} /> : <Spinner />}
        </Panel>
      </div>
    </>
  );
}
