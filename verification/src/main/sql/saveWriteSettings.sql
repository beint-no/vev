-- vev.name: saveWriteSettings :one
-- vev.nullable: amount
-- vev.notnull: label,normalized,revision,updated_at
INSERT INTO write_settings(tenant_id,amount,label) VALUES(:tenantId,:amount,:label)
ON CONFLICT (tenant_id) DO UPDATE SET amount=EXCLUDED.amount,label=EXCLUDED.label,revision=write_settings.revision+1
RETURNING old.amount AS old_amount,new.amount AS amount,label,normalized,revision,updated_at;
