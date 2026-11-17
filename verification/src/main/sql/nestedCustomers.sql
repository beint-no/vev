-- vev.name: nestedCustomers :many
-- vev.rows: 5
WITH selected AS (SELECT * FROM customer WHERE tenant_id=:tenantId)
SELECT id,name FROM selected ORDER BY id;
