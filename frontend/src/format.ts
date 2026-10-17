const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' });

export const fmtMoney = (n: number | null | undefined) => (n == null ? '—' : money.format(n));

export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—');

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export const humanize = (s: string | null | undefined) =>
  s ? s.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase()) : '—';
