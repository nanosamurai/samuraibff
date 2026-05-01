-- Migration rollback: drop workflow configuration tables

DROP TABLE IF EXISTS workflow_defaults;
DROP TABLE IF EXISTS workflows;
