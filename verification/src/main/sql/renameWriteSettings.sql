-- vev.name: renameWriteSettings :optional
-- vev.notnull: old_label,label,revision
UPDATE write_settings SET label=:label,revision=revision+1 WHERE tenant_id=:tenantId AND revision=:expectedRevision
RETURNING WITH (OLD AS previous,NEW AS current) previous.label AS old_label,current.label AS label,current.revision AS revision;
