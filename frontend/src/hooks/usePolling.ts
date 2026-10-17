import { useCallback, useEffect, useRef, useState } from 'react';

export interface Polled<T> {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  refresh: () => Promise<void>;
  lastUpdated: Date | null;
}

/**
 * Fetches on mount and then every `intervalMs` (0 fetches once). Sagas are asynchronous by design -
 * the API answers 202 and the outcome arrives later - so most screens poll. Polling pauses while
 * the tab is hidden, and a slow response never overwrites a newer one.
 */
export function usePolling<T>(fetcher: () => Promise<T>, intervalMs: number, deps: unknown[] = []): Polled<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const seq = useRef(0);

  const run = useCallback(async () => {
    const mine = ++seq.current;
    try {
      const result = await fetcherRef.current();
      if (mine !== seq.current) return;
      setData(result);
      setError(null);
      setLastUpdated(new Date());
    } catch (e) {
      if (mine !== seq.current) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, []);

  // A different resource (deps) starts from scratch; a different interval just reschedules.
  useEffect(() => {
    setLoading(true);
    setData(undefined);
    setError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    run();
    if (!intervalMs) return;
    const id = window.setInterval(() => {
      if (document.visibilityState === 'visible') run();
    }, intervalMs);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, run, ...deps]);

  return { data, error, loading, refresh: run, lastUpdated };
}
