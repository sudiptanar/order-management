import type { Notification, Order, Payment, Reservation, SagaState } from './api/types';

export type Tone = 'good' | 'warning' | 'serious' | 'critical' | 'info' | 'neutral';

/** How each saga state should read at a glance. Status colors are reserved for these meanings. */
export const SAGA_STATE_META: Record<SagaState, { tone: Tone; label: string; inFlight: boolean }> = {
  AWAITING_INVENTORY: { tone: 'info', label: 'Awaiting inventory', inFlight: true },
  AWAITING_PAYMENT: { tone: 'info', label: 'Awaiting payment', inFlight: true },
  COMPLETED: { tone: 'good', label: 'Completed', inFlight: false },
  COMPENSATING_PAYMENT: { tone: 'warning', label: 'Compensating payment', inFlight: true },
  COMPENSATING_INVENTORY: { tone: 'warning', label: 'Compensating inventory', inFlight: true },
  COMPENSATED: { tone: 'serious', label: 'Compensated', inFlight: false },
  FAILED: { tone: 'critical', label: 'Failed', inFlight: false },
  COMPENSATION_FAILED: { tone: 'critical', label: 'Compensation failed', inFlight: false },
};

export const SAGA_STATES = Object.keys(SAGA_STATE_META) as SagaState[];

export const ORDER_STATUS_TONE: Record<string, Tone> = {
  CREATED: 'info',
  INVENTORY_RESERVED: 'info',
  PAYMENT_COMPLETED: 'info',
  CONFIRMED: 'good',
  CANCELLED: 'critical',
};

export const PAYMENT_STATUS_TONE: Record<string, Tone> = {
  AUTHORIZED: 'good',
  DECLINED: 'critical',
  REFUNDED: 'warning',
};

export const isInFlight = (o: Order) => !!o.sagaState && SAGA_STATE_META[o.sagaState].inFlight;

/** Sagas in flight longer than this probably have a command sitting in a dead-letter topic. */
export const STUCK_AFTER_MS = 30_000;

export const looksStuck = (o: Order) =>
  isInFlight(o) && Date.now() - new Date(o.updatedAt).getTime() > STUCK_AFTER_MS;

/**
 * The saga only persists its current state, not its history. The path it took is recoverable
 * because the state machine is small: the only ambiguity is whether COMPENSATING_INVENTORY was
 * reached directly (payment declined) or via a refund (confirm failed) - and the payment row,
 * owned by payment-service, settles that.
 */
export function sagaPath(state: SagaState, step: string | null, payment: Payment | null | undefined): SagaState[] {
  const refunded = payment?.status === 'REFUNDED';
  switch (state) {
    case 'AWAITING_INVENTORY':
      return ['AWAITING_INVENTORY'];
    case 'AWAITING_PAYMENT':
      return ['AWAITING_INVENTORY', 'AWAITING_PAYMENT'];
    case 'COMPLETED':
      return ['AWAITING_INVENTORY', 'AWAITING_PAYMENT', 'COMPLETED'];
    case 'FAILED':
      return ['AWAITING_INVENTORY', 'FAILED'];
    case 'COMPENSATING_PAYMENT':
      return ['AWAITING_INVENTORY', 'AWAITING_PAYMENT', 'COMPENSATING_PAYMENT'];
    case 'COMPENSATING_INVENTORY':
    case 'COMPENSATED':
      return [
        'AWAITING_INVENTORY',
        'AWAITING_PAYMENT',
        ...(refunded ? (['COMPENSATING_PAYMENT'] as SagaState[]) : []),
        'COMPENSATING_INVENTORY',
        ...(state === 'COMPENSATED' ? (['COMPENSATED'] as SagaState[]) : []),
      ];
    case 'COMPENSATION_FAILED':
      return step === 'REFUND_PAYMENT'
        ? ['AWAITING_INVENTORY', 'AWAITING_PAYMENT', 'COMPENSATING_PAYMENT', 'COMPENSATION_FAILED']
        : [
            'AWAITING_INVENTORY',
            'AWAITING_PAYMENT',
            ...(refunded ? (['COMPENSATING_PAYMENT'] as SagaState[]) : []),
            'COMPENSATING_INVENTORY',
            'COMPENSATION_FAILED',
          ];
  }
}

export type StepStatus = 'done' | 'active' | 'failed' | 'pending' | 'skipped';

export interface TimelineStep {
  key: string;
  title: string;
  service: string;
  status: StepStatus;
  detail: string;
  compensation?: boolean;
}

/**
 * One row per unit of work, forward steps then compensations, each with its status as seen by
 * the service that owns it - so a mismatch between the orchestrator's view and a participant's
 * shows up here rather than staying hidden.
 */
export function buildTimeline(
  order: Order,
  reservations: Reservation[] | undefined,
  payment: Payment | null | undefined,
  notifications: Notification[] | undefined,
): TimelineStep[] {
  const state = order.sagaState;
  const step = order.sagaStep;
  const path = state ? sagaPath(state, step, payment) : [];
  const reached = (s: SagaState) => path.includes(s);
  const stuck = looksStuck(order);

  const steps: TimelineStep[] = [];

  // 1. Reserve inventory
  const held = reservations ?? [];
  let reserve: TimelineStep = {
    key: 'reserve',
    title: 'Reserve inventory',
    service: 'inventory-service',
    status: 'pending',
    detail: 'Waiting for the orchestrator to send the command.',
  };
  if (held.length > 0) {
    reserve = {
      ...reserve,
      status: 'done',
      detail: held.map((r) => `${r.quantity} × ${r.productId} (${r.status.toLowerCase()})`).join(', '),
    };
  } else if (state === 'AWAITING_INVENTORY') {
    reserve = {
      ...reserve,
      status: 'active',
      detail: stuck
        ? 'No reply for a while. The command may have been retried and dead-lettered - check Operations.'
        : 'Command sent; waiting for inventory-service to reply.',
    };
  } else if (state === 'FAILED') {
    reserve = { ...reserve, status: 'failed', detail: order.failureReason ?? 'Inventory rejected the order.' };
  }
  steps.push(reserve);

  // 2. Process payment
  let pay: TimelineStep = {
    key: 'payment',
    title: 'Process payment',
    service: 'payment-service',
    status: state === 'FAILED' ? 'skipped' : 'pending',
    detail: state === 'FAILED' ? 'Never attempted - nothing had been committed.' : 'Not started.',
  };
  if (payment) {
    pay = {
      ...pay,
      status: payment.status === 'DECLINED' ? 'failed' : 'done',
      detail:
        payment.status === 'DECLINED'
          ? `Declined: ${payment.failureReason ?? 'no reason given'}`
          : `Authorised ${payment.authorizationRef ?? ''} for ${payment.amount}`.trim(),
    };
  } else if (state === 'AWAITING_PAYMENT') {
    pay = {
      ...pay,
      status: 'active',
      detail: stuck
        ? 'No reply for a while. The command may be in a dead-letter topic - check Operations.'
        : 'Command sent; waiting for payment-service to reply.',
    };
  }
  steps.push(pay);

  // 3. Confirm (local to order-service)
  let confirm: TimelineStep = {
    key: 'confirm',
    title: 'Confirm order',
    service: 'order-service (local)',
    status: state === 'FAILED' || payment?.status === 'DECLINED' ? 'skipped' : 'pending',
    detail: 'Final local step - no message, just a commit.',
  };
  if (state === 'COMPLETED') confirm = { ...confirm, status: 'done', detail: 'Order confirmed.' };
  else if (reached('COMPENSATING_PAYMENT'))
    confirm = { ...confirm, status: 'failed', detail: order.failureReason ?? 'Local confirmation failed.' };
  steps.push(confirm);

  // 4. Compensations, in the order they run: refund first, then release.
  if (reached('COMPENSATING_PAYMENT')) {
    const failedHere = state === 'COMPENSATION_FAILED' && step === 'REFUND_PAYMENT';
    steps.push({
      key: 'refund',
      title: 'Refund payment',
      service: 'payment-service',
      compensation: true,
      status: payment?.status === 'REFUNDED' ? 'done' : failedHere ? 'failed' : 'active',
      detail:
        payment?.status === 'REFUNDED'
          ? `Refunded ${payment.amount}.`
          : failedHere
            ? 'Refund failed - manual intervention required.'
            : 'Refund command sent; waiting for reply.',
    });
  }
  if (reached('COMPENSATING_INVENTORY')) {
    const released = held.length > 0 && held.every((r) => r.status === 'RELEASED');
    const failedHere = state === 'COMPENSATION_FAILED' && step === 'RELEASE_INVENTORY';
    steps.push({
      key: 'release',
      title: 'Release inventory',
      service: 'inventory-service',
      compensation: true,
      status: released || state === 'COMPENSATED' ? 'done' : failedHere ? 'failed' : 'active',
      detail: released
        ? 'Reserved stock given back.'
        : failedHere
          ? 'Release failed - stock is stranded. Manual intervention required.'
          : 'Release command sent; waiting for reply.',
    });
  }

  // 5. Notifications (fan-out from order.events; nobody blocks on it)
  // ORDER_CREATED is announced up front; the step is done once the outcome has been announced too.
  const notes = notifications ?? [];
  const types = [...new Set(notes.map((n) => n.eventType))];
  const outcomeSent = types.some((t) => t === 'ORDER_CONFIRMED' || t === 'ORDER_CANCELLED');
  const finished = state != null && !SAGA_STATE_META[state].inFlight && state !== 'COMPENSATION_FAILED';
  steps.push({
    key: 'notify',
    title: 'Notify customer',
    service: 'notification-service',
    status: outcomeSent ? 'done' : finished ? 'active' : 'pending',
    detail:
      (notes.length > 0 ? `Sent: ${types.join(', ')}. ` : '') +
      (outcomeSent
        ? ''
        : finished
          ? 'Outcome event published; waiting for notification-service to consume it.'
          : 'Outcome is announced once the saga finishes.'),
  });

  return steps;
}
