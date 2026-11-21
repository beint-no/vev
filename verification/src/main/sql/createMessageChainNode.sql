-- vev.name: createMessageChainNode :one
-- vev.nullable: parentId
-- vev.notnull: id
INSERT INTO message_chain (tenant_id, parent_id, label)
VALUES (:tenantId, :parentId, :label)
RETURNING id;
