import type {
  CreateOrderRequest,
  DltDepth,
  DltRecord,
  Health,
  Notification,
  Order,
  OutboxHealth,
  Payment,
  ReplayResult,
  Reservation,
  Simulation,
  Stock,
} from './types';

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}) },
    });
  } catch {
    throw new ApiError(0, `Network error calling ${path}`);
  }
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    let fromService = false;
    try {
      const body = await res.json();
      // Spring's ProblemDetail carries the useful message in `detail`.
      detail = body.detail ?? body.message ?? body.error ?? detail;
      fromService = true;
    } catch {
      /* body was not JSON */
    }
    // A non-JSON 5xx comes from the proxy, not Spring: the service itself is down.
    if (!fromService && res.status >= 500) {
      detail = `Service unreachable (${res.status}). Is it running?`;
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

/** For lookups where 404 means "nothing yet" rather than an error, e.g. no payment taken. */
async function optional<T>(path: string): Promise<T | null> {
  try {
    return await request<T>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });
const enc = encodeURIComponent;

export const api = {
  orders: {
    list: (limit = 100) => request<Order[]>(`/api/orders?limit=${limit}`),
    get: (id: string) => request<Order>(`/api/orders/${enc(id)}`),
    create: (req: CreateOrderRequest) => request<Order>('/api/orders', { method: 'POST', ...json(req) }),
    sagaSummary: () => request<Record<string, number>>('/api/orders/status/summary'),
  },
  inventory: {
    list: () => request<Stock[]>('/api/inventory'),
    get: (productId: string) => optional<Stock>(`/api/inventory/${enc(productId)}`),
    setStock: (productId: string, availableQty: number) =>
      request<Stock>(`/api/inventory/${enc(productId)}`, { method: 'PUT', ...json({ availableQty }) }),
    reservations: (orderId: string) => request<Reservation[]>(`/api/inventory/reservations/${enc(orderId)}`),
    simulation: () => request<Simulation>('/api/inventory/simulation'),
    setSimulation: (failingProductId: string) =>
      request<Simulation>('/api/inventory/simulation', { method: 'PUT', ...json({ failingProductId }) }),
  },
  payments: {
    list: (limit = 100) => request<Payment[]>(`/api/payments?limit=${limit}`),
    forOrder: (orderId: string) => optional<Payment>(`/api/payments/order/${enc(orderId)}`),
  },
  notifications: {
    list: (limit = 100) => request<Notification[]>(`/api/notifications?limit=${limit}`),
    forOrder: (orderId: string) => request<Notification[]>(`/api/notifications/order/${enc(orderId)}`),
  },
  ops: {
    topics: () => request<string[]>('/api/ops/topics'),
    dlt: () => request<Record<string, DltDepth>>('/api/ops/dlt'),
    peek: (originTopic: string, max = 50) => request<DltRecord[]>(`/api/ops/dlt/${enc(originTopic)}?max=${max}`),
    replay: (originTopic: string, max = 20) =>
      request<ReplayResult>(`/api/ops/dlt/${enc(originTopic)}/replay?max=${max}`, { method: 'POST' }),
    outbox: () => request<OutboxHealth>('/api/ops/outbox'),
  },
  health: (service: ServiceName) => request<Health>(`/health/${service}`),
};

export type ServiceName = 'order' | 'inventory' | 'payment' | 'notification';

export const SERVICES: { name: ServiceName; label: string; port: number }[] = [
  { name: 'order', label: 'order-service', port: 8081 },
  { name: 'inventory', label: 'inventory-service', port: 8082 },
  { name: 'payment', label: 'payment-service', port: 8083 },
  { name: 'notification', label: 'notification-service', port: 8084 },
];
