-- vev.name: nestedCustomers :many
WITH selected AS (SELECT * FROM customer WHERE tenant_id=:tenantId)
SELECT id,name FROM selected ORDER BY id;
