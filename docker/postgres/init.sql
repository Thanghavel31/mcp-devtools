-- ============================================================
-- COSMOS Vehicle Telemetry Database — Seed Script
-- Mirrors a real OTA/connected-vehicle platform schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ------------------------------------------------------------
-- 1. Vehicles table (fleet registry)
-- ------------------------------------------------------------
CREATE TABLE vehicles (
    vehicle_id   VARCHAR(20) PRIMARY KEY,
    vin          VARCHAR(17) NOT NULL UNIQUE,
    model        VARCHAR(50) NOT NULL,
    model_year   INT NOT NULL,
    region       VARCHAR(20) NOT NULL,
    firmware_ver VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Insert 500 vehicles across regions and models
INSERT INTO vehicles (vehicle_id, vin, model, model_year, region, firmware_ver, status)
SELECT
    'V-' || LPAD(i::TEXT, 6, '0'),
    UPPER(SUBSTR(MD5(i::TEXT), 1, 17)),
    (ARRAY['Jeep Compass', 'Fiat 500e', 'Ram 1500', 'Peugeot 3008', 'Citroen C5'])[1 + (i % 5)],
    2022 + (i % 4),
    (ARRAY['NA', 'EU', 'APAC', 'LATAM'])[1 + (i % 4)],
    'v3.' || (i % 5) || '.' || (i % 10),
    CASE WHEN i % 50 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 500) AS s(i);

-- ------------------------------------------------------------
-- 2. Vehicle events table (telemetry — the "big" table)
--    Deliberately NO index on vehicle_id to demo slow queries
-- ------------------------------------------------------------
CREATE TABLE vehicle_events (
    event_id       BIGSERIAL PRIMARY KEY,
    vehicle_id     VARCHAR(20) NOT NULL,
    event_type     VARCHAR(30) NOT NULL,
    event_data     JSONB NOT NULL DEFAULT '{}',
    severity       VARCHAR(10) NOT NULL DEFAULT 'INFO',
    event_timestamp TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Insert 50,000 telemetry events (enough to show seq scan impact)
INSERT INTO vehicle_events (vehicle_id, event_type, event_data, severity, event_timestamp)
SELECT
    'V-' || LPAD((1 + (i % 500))::TEXT, 6, '0'),
    (ARRAY['HEARTBEAT', 'LOCATION', 'DIAGNOSTIC', 'ALERT', 'OTA_STATUS', 'BATTERY', 'ENGINE_TEMP'])[1 + (i % 7)],
    jsonb_build_object(
        'lat', 12.9 + (random() * 2),
        'lng', 77.5 + (random() * 2),
        'value', (random() * 100)::INT
    ),
    (ARRAY['INFO', 'INFO', 'INFO', 'WARN', 'ERROR'])[1 + (i % 5)],
    NOW() - (i || ' minutes')::INTERVAL
FROM generate_series(1, 50000) AS s(i);

-- NOTE: Intentionally NO index on (vehicle_id, event_timestamp)
-- This creates the slow query scenario from INC-2003

-- ------------------------------------------------------------
-- 3. OTA campaigns table
-- ------------------------------------------------------------
CREATE TABLE ota_campaigns (
    campaign_id    VARCHAR(20) PRIMARY KEY,
    target_model   VARCHAR(50) NOT NULL,
    target_region  VARCHAR(20),
    firmware_from  VARCHAR(20) NOT NULL,
    firmware_to    VARCHAR(20) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_vehicles INT NOT NULL DEFAULT 0,
    success_count  INT NOT NULL DEFAULT 0,
    failure_count  INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at     TIMESTAMP,
    completed_at   TIMESTAMP
);

INSERT INTO ota_campaigns VALUES
    ('CAMP-4521', 'Jeep Compass', 'NA', 'v3.2.1', 'v3.2.2', 'IN_PROGRESS', 15000, 8500, 120, NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day', NULL),
    ('CAMP-4520', 'Fiat 500e', 'EU', 'v3.1.0', 'v3.1.1', 'COMPLETED', 8000, 7950, 50, NOW() - INTERVAL '10 days', NOW() - INTERVAL '9 days', NOW() - INTERVAL '7 days'),
    ('CAMP-4519', 'Ram 1500', 'NA', 'v3.0.5', 'v3.0.6', 'COMPLETED', 22000, 21800, 200, NOW() - INTERVAL '20 days', NOW() - INTERVAL '19 days', NOW() - INTERVAL '15 days'),
    ('CAMP-4522', 'Peugeot 3008', 'EU', 'v3.3.0', 'v3.3.1', 'PENDING', 5000, 0, 0, NOW(), NULL, NULL);

-- ------------------------------------------------------------
-- 4. Incident log table
-- ------------------------------------------------------------
CREATE TABLE incident_log (
    incident_id   VARCHAR(20) PRIMARY KEY,
    severity      VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    service       VARCHAR(50) NOT NULL,
    owner         VARCHAR(50),
    opened_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMP,
    mttr_minutes  INT
);

INSERT INTO incident_log VALUES
    ('INC-2001', 'SEV1', 'OPEN', 'OTA campaign stalled — Kafka consumer lag >100k', 'cosmos-ota-service', 'Thanghavel', NOW() - INTERVAL '12 hours', NULL, NULL),
    ('INC-2002', 'SEV2', 'MITIGATED', 'Telemetry ingestion latency spike — p99 > 5s', 'cosmos-telemetry-service', 'Thanghavel', NOW() - INTERVAL '2 days', NULL, NULL),
    ('INC-2003', 'SEV1', 'RESOLVED', 'PostgreSQL connection pool exhaustion — vehicle-status API 503s', 'cosmos-vehicle-status', 'Thanghavel', NOW() - INTERVAL '7 days', NOW() - INTERVAL '7 days' + INTERVAL '45 minutes', 45),
    ('INC-2004', 'SEV3', 'RESOLVED', 'Stale Redis cache returning outdated firmware version', 'cosmos-fleet-monitor', NULL, NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days' + INTERVAL '120 minutes', 120),
    ('INC-2005', 'SEV2', 'OPEN', 'mTLS certificate expiry — 200 devices failing to connect', 'cosmos-device-connectivity', 'Thanghavel', NOW() - INTERVAL '6 hours', NULL, NULL);

-- ------------------------------------------------------------
-- 5. Run a few queries so pg_stat_statements has data
-- ------------------------------------------------------------
SELECT * FROM vehicle_events WHERE vehicle_id = 'V-000001' ORDER BY event_timestamp DESC LIMIT 10;
SELECT * FROM vehicle_events WHERE vehicle_id = 'V-000250' AND event_type = 'ALERT';
SELECT COUNT(*) FROM vehicle_events WHERE severity = 'ERROR';
SELECT vehicle_id, COUNT(*) FROM vehicle_events GROUP BY vehicle_id ORDER BY COUNT(*) DESC LIMIT 10;

-- Grant read-only permissions
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO cosmos_reader;
