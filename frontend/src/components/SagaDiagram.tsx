import type { SagaState } from '../api/types';
import { SAGA_STATE_META } from '../saga';

// Grid positions mirror the diagram in SagaState.java: forward path on top, failure and
// compensation states below it.
const COL = [16, 232, 448];
const ROW = [16, 104, 192];
const W = 176;
const H = 46;

const NODES: Record<SagaState, { x: number; y: number }> = {
  AWAITING_INVENTORY: { x: COL[0], y: ROW[0] },
  AWAITING_PAYMENT: { x: COL[1], y: ROW[0] },
  COMPLETED: { x: COL[2], y: ROW[0] },
  FAILED: { x: COL[0], y: ROW[1] },
  COMPENSATING_INVENTORY: { x: COL[1], y: ROW[1] },
  COMPENSATING_PAYMENT: { x: COL[2], y: ROW[1] },
  COMPENSATED: { x: COL[1], y: ROW[2] },
  COMPENSATION_FAILED: { x: COL[2], y: ROW[2] },
};

const EDGES: { from: SagaState; to: SagaState; label: string }[] = [
  { from: 'AWAITING_INVENTORY', to: 'AWAITING_PAYMENT', label: 'reserved' },
  { from: 'AWAITING_PAYMENT', to: 'COMPLETED', label: 'paid + confirmed' },
  { from: 'AWAITING_INVENTORY', to: 'FAILED', label: 'rejected' },
  { from: 'AWAITING_PAYMENT', to: 'COMPENSATING_INVENTORY', label: 'declined' },
  { from: 'AWAITING_PAYMENT', to: 'COMPENSATING_PAYMENT', label: 'confirm failed' },
  { from: 'COMPENSATING_PAYMENT', to: 'COMPENSATING_INVENTORY', label: 'refunded' },
  { from: 'COMPENSATING_INVENTORY', to: 'COMPENSATED', label: 'released' },
  { from: 'COMPENSATING_PAYMENT', to: 'COMPENSATION_FAILED', label: 'refund failed' },
  { from: 'COMPENSATING_INVENTORY', to: 'COMPENSATION_FAILED', label: 'release failed' },
];

/** Picks the attachment points on the facing sides of two boxes. */
function anchor(from: SagaState, to: SagaState) {
  const a = NODES[from];
  const b = NODES[to];
  const ac = { x: a.x + W / 2, y: a.y + H / 2 };
  const bc = { x: b.x + W / 2, y: b.y + H / 2 };
  const dx = bc.x - ac.x;
  const dy = bc.y - ac.y;
  if (dy === 0) {
    const s = Math.sign(dx);
    return { x1: ac.x + (s * W) / 2, y1: ac.y, x2: bc.x - (s * W) / 2, y2: bc.y };
  }
  if (dx === 0) {
    const s = Math.sign(dy);
    return { x1: ac.x, y1: ac.y + (s * H) / 2, x2: bc.x, y2: bc.y - (s * H) / 2 };
  }
  // Diagonal: leave from the bottom edge, arrive at the side.
  const s = Math.sign(dx);
  return { x1: ac.x + s * (W / 2 - 24), y1: ac.y + H / 2, x2: bc.x - (s * W) / 2, y2: bc.y };
}

export function SagaDiagram({ path, live }: { path: SagaState[]; live: boolean }) {
  const current = path[path.length - 1];
  const visited = new Set(path);
  const traversed = new Set(path.slice(1).map((s, i) => `${path[i]}>${s}`));

  return (
    <div className="saga-diagram">
      <svg viewBox="0 0 640 256" role="img" aria-label={`Saga state machine, current state ${current ?? 'unknown'}`}>
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="arrow-idle" />
          </marker>
          <marker id="arrow-on" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="arrow-on" />
          </marker>
        </defs>

        {EDGES.map((e) => {
          const on = traversed.has(`${e.from}>${e.to}`);
          const { x1, y1, x2, y2 } = anchor(e.from, e.to);
          const mx = (x1 + x2) / 2;
          const my = (y1 + y2) / 2;
          return (
            <g key={`${e.from}-${e.to}`} className={on ? 'edge edge-on' : 'edge'}>
              <line x1={x1} y1={y1} x2={x2} y2={y2} markerEnd={on ? 'url(#arrow-on)' : 'url(#arrow)'} />
              <text x={mx + (y1 === y2 ? 0 : 6)} y={my - (y1 === y2 ? 6 : 0)} textAnchor={y1 === y2 ? 'middle' : 'start'}>
                {e.label}
              </text>
            </g>
          );
        })}

        {(Object.keys(NODES) as SagaState[]).map((s) => {
          const { x, y } = NODES[s];
          const meta = SAGA_STATE_META[s];
          const isCurrent = s === current;
          const cls = ['node', `tone-${meta.tone}`, visited.has(s) ? 'node-visited' : '', isCurrent ? 'node-current' : '', isCurrent && live ? 'node-live' : '']
            .filter(Boolean)
            .join(' ');
          return (
            <g key={s} className={cls}>
              <rect x={x} y={y} width={W} height={H} rx={10} />
              <text x={x + W / 2} y={y + H / 2 + 4} textAnchor="middle">
                {meta.label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
