-- vev.name: conditionalWriteSettings :optional
-- vev.nullable: amount
INSERT INTO write_settings(tenant_id,amount) VALUES(:tenantId,:amount)
ON CONFLICT (tenant_id) DO UPDATE SET amount=EXCLUDED.amount,revision=write_settings.revision+1
WHERE write_settings.revision=:expectedRevision
RETURNING amount,revision;
