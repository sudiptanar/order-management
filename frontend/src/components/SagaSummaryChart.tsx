import { useNavigate } from 'react-router-dom';
import { SAGA_STATE_META, SAGA_STATES } from '../saga';

/**
 * Magnitude per saga state: a horizontal bar list on one axis, ordered by the state machine
 * rather than by count so the rows never jump around between refreshes. Bars carry the status
 * tone; values and labels stay in text ink. Click a row to see those orders.
 */
export function SagaSummaryChart({ summary }: { summary: Record<string, number> }) {
  const navigate = useNavigate();
  const total = SAGA_STATES.reduce((n, s) => n + (summary[s] ?? 0), 0);
  const max = Math.max(1, ...SAGA_STATES.map((s) => summary[s] ?? 0));

  return (
    <div className="bar-list" role="table" aria-label="Sagas by state">
      {SAGA_STATES.map((s) => {
        const n = summary[s] ?? 0;
        const meta = SAGA_STATE_META[s];
        const pct = total ? Math.round((n / total) * 1000) / 10 : 0;
        return (
          <button
            key={s}
            role="row"
            className="bar-row"
            onClick={() => navigate(`/orders?saga=${s}`)}
            title={`${meta.label}: ${n} saga${n === 1 ? '' : 's'} (${pct}% of ${total})`}
          >
            <span role="cell" className="bar-label">
              {meta.label}
            </span>
            <span role="cell" className="bar-track">
              {n > 0 && <span className={`bar-fill tone-${meta.tone}`} style={{ width: `${(n / max) * 100}%` }} />}
            </span>
            <span role="cell" className="bar-value">
              {n}
              <span className="bar-pct">{total ? `${pct}%` : ''}</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
