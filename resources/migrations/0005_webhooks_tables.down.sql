-- Migration rollback: drop webhook configuration tables

DROP TABLE IF EXISTS tenant_webhook_defaults;
DROP TABLE IF EXISTS webhook_subscriptions;
DROP TABLE IF EXISTS webhooks;
