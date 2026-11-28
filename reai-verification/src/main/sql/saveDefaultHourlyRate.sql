-- vev.name: saveDefaultHourlyRate :one
-- vev.nullable: defaultHourlyRate
INSERT INTO project_settings(tenant_id, default_hourly_rate)
VALUES (:tenantId, :defaultHourlyRate)
ON CONFLICT (tenant_id) DO UPDATE SET default_hourly_rate = EXCLUDED.default_hourly_rate
RETURNING default_hourly_rate;
