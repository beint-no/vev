-- vev.name: defaultHourlyRate :optional
SELECT default_hourly_rate FROM project_settings WHERE tenant_id = :tenantId;
