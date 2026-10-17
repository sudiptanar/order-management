import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The four services each own a distinct /api prefix, so the dev server can proxy them all from
// one origin. That keeps the browser same-origin and means the services need no CORS config.
// Override any target with an env var when a service runs somewhere other than localhost.
const order = process.env.ORDER_SERVICE_URL ?? 'http://localhost:8081';
const inventory = process.env.INVENTORY_SERVICE_URL ?? 'http://localhost:8082';
const payment = process.env.PAYMENT_SERVICE_URL ?? 'http://localhost:8083';
const notification = process.env.NOTIFICATION_SERVICE_URL ?? 'http://localhost:8084';

const health = (target: string) => ({
  target,
  changeOrigin: true,
  rewrite: () => '/actuator/health',
});

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/orders': order,
      '/api/ops': order,
      '/api/inventory': inventory,
      '/api/payments': payment,
      '/api/notifications': notification,
      '/health/order': health(order),
      '/health/inventory': health(inventory),
      '/health/payment': health(payment),
      '/health/notification': health(notification),
    },
  },
});
