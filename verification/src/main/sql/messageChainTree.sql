-- vev.name: messageChainTree :many
-- vev.notnull: root_id, id, label
WITH RECURSIVE chain AS (
    SELECT id AS root_id, id, parent_id, label
    FROM message_chain
    WHERE tenant_id = :tenantId
      AND id = ANY(:rootIds::int[])
    UNION ALL
    SELECT c.root_id, m.id, m.parent_id, m.label
    FROM message_chain m
    JOIN chain c ON m.parent_id = c.id
    WHERE m.tenant_id = :tenantId
)
SELECT root_id, id, parent_id, label
FROM chain
ORDER BY root_id, id;
