-- vev.name: departmentTotals :many
-- vev.notnull: id,total
SELECT d.id,d.name,p.total FROM departments d
CROSS JOIN LATERAL (
    SELECT coalesce(sum(amount),0) AS total FROM postings p
    WHERE p.department_id=d.id AND p.tenant_id=d.tenant_id
) p WHERE d.tenant_id=:tenantId ORDER BY d.id;
