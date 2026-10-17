// Mirrors the Java records returned by each service's REST API.

export type OrderStatus = 'CREATED' | 'INVENTORY_RESERVED' | 'PAYMENT_COMPLETED' | 'CONFIRMED' | 'CANCELLED';

export type SagaState =
  | 'AWAITING_INVENTORY'
  | 'AWAITING_PAYMENT'
  | 'COMPLETED'
  | 'COMPENSATING_PAYMENT'
  | 'COMPENSATING_INVENTORY'
  | 'COMPENSATED'
  | 'FAILED'
  | 'COMPENSATION_FAILED';

export type SagaStep = 'RESERVE_INVENTORY' | 'PROCESS_PAYMENT' | 'REFUND_PAYMENT' | 'RELEASE_INVENTORY';

export interface OrderItem {
  productId: string;
  quantity: number;
  unitPrice: number;
}

export interface Order {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  totalAmount: number;
  failureReason: string | null;
  sagaId: string | null;
  sagaState: SagaState | null;
  sagaStep: SagaStep | null;
  createdAt: string;
  updatedAt: string;
  items: OrderItem[];
}

export interface CreateOrderRequest {
  customerId: string;
  items: OrderItem[];
  simulateConfirmFailure: boolean;
}

export interface Stock {
  productId: string;
  availableQty: number;
  reservedQty: number;
  totalQty: number;
}

export interface Reservation {
  id: string;
  orderId: string;
  productId: string;
  quantity: number;
  status: 'ACTIVE' | 'RELEASED';
}

export interface Simulation {
  failingProductId: string | null;
}

export type PaymentStatus = 'AUTHORIZED' | 'DECLINED' | 'REFUNDED';

export interface Payment {
  id: string;
  orderId: string;
  customerId: string;
  amount: number;
  status: PaymentStatus;
  authorizationRef: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Notification {
  id: string;
  orderId: string;
  customerId: string;
  channel: string;
  eventType: string;
  subject: string;
  body: string;
  createdAt: string;
}

export interface DltDepth {
  total: number;
  pendingReplay: number;
}

export interface DltRecord {
  topic: string;
  partition: number;
  offset: number;
  key: string | null;
  payload: string | null;
  originalTopic: string | null;
  exceptionType: string | null;
  exceptionMessage: string | null;
  timestamp: string;
}

export interface ReplayResult {
  fromTopic: string;
  toTopic: string;
  replayed: number;
  note: string;
}

export interface OutboxFailure {
  id: string;
  topic: string;
  attempts: number;
  lastError: string;
}

/** Counts keyed by lower-cased OutboxStatus, plus the most recent failures. */
export type OutboxHealth = Record<string, number | OutboxFailure[]> & { recentFailures: OutboxFailure[] };

export interface Health {
  status: string;
}
