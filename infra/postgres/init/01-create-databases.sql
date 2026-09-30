-- One Postgres instance, four logically isolated databases with four distinct owners.
-- "Database per service" means no shared tables and no cross-service joins.
-- Separate credentials make cheating impossible: order_user literally cannot read inventorydb.

CREATE USER order_user        WITH PASSWORD 'order_pass';
CREATE USER inventory_user    WITH PASSWORD 'inventory_pass';
CREATE USER payment_user      WITH PASSWORD 'payment_pass';
CREATE USER notification_user WITH PASSWORD 'notification_pass';

CREATE DATABASE orderdb        OWNER order_user;
CREATE DATABASE inventorydb    OWNER inventory_user;
CREATE DATABASE paymentdb      OWNER payment_user;
CREATE DATABASE notificationdb OWNER notification_user;

-- Postgres 15+ no longer grants CREATE on the public schema to everyone,
-- so hand the schema to each service owner explicitly.
\c orderdb
ALTER SCHEMA public OWNER TO order_user;
GRANT ALL ON SCHEMA public TO order_user;

\c inventorydb
ALTER SCHEMA public OWNER TO inventory_user;
GRANT ALL ON SCHEMA public TO inventory_user;

\c paymentdb
ALTER SCHEMA public OWNER TO payment_user;
GRANT ALL ON SCHEMA public TO payment_user;

\c notificationdb
ALTER SCHEMA public OWNER TO notification_user;
GRANT ALL ON SCHEMA public TO notification_user;