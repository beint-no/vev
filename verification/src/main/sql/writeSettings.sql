-- vev.name: writeSettings :optional
SELECT amount,label,normalized,revision,updated_at FROM write_settings WHERE tenant_id=:tenantId;
