import { api, SERVICES } from '../api/client';
import { usePolling } from '../hooks/usePolling';

type Status = 'UP' | 'DOWN' | 'UNKNOWN';

async function checkAll(): Promise<Record<string, Status>> {
  const results = await Promise.all(
    SERVICES.map(async (s) => {
      try {
        const h = await api.health(s.name);
        return [s.name, h.status === 'UP' ? 'UP' : 'DOWN'] as const;
      } catch {
        return [s.name, 'DOWN'] as const;
      }
    }),
  );
  return Object.fromEntries(results);
}

export function useServiceHealth(intervalMs = 10_000) {
  return usePolling(checkAll, intervalMs);
}

export function ServiceHealthList({ compact = false }: { compact?: boolean }) {
  const { data } = useServiceHealth();
  return (
    <ul className={compact ? 'health-list compact' : 'health-list'}>
      {SERVICES.map((s) => {
        const status: Status = data?.[s.name] ?? 'UNKNOWN';
        return (
          <li key={s.name} title={`${s.label} on :${s.port} — ${status}`}>
            <span className={`health-dot health-${status.toLowerCase()}`} aria-hidden />
            <span className="health-name">{s.label}</span>
            {!compact && <span className="health-port">:{s.port}</span>}
            <span className={`health-status health-${status.toLowerCase()}`}>{status === 'UNKNOWN' ? '…' : status}</span>
          </li>
        );
      })}
    </ul>
  );
}
