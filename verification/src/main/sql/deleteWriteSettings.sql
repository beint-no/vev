-- vev.name: deleteWriteSettings :optional
-- vev.notnull: label
DELETE FROM write_settings WHERE tenant_id=:tenantId RETURNING old.amount AS amount,old.label AS label,new.amount AS new_amount;
