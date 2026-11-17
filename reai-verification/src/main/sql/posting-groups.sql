-- vev.name: supplierPostingGroupCounts :many
-- vev.rows: 100000
-- vev.notnull: posting_group_id,posting_count
SELECT membership.posting_group_id, count(p.id) AS posting_count
FROM supplier_posting_group_memberships membership
JOIN postings p ON p.id = membership.posting_id AND p.tenant_id = membership.tenant_id
WHERE membership.tenant_id = :tenantId AND membership.posting_group_id = ANY(:postingGroupIds::int[])
  AND p.account_number = :supplierAccountNumber
GROUP BY membership.posting_group_id;

-- vev.name: customerPostingGroupCounts :many
-- vev.rows: 100000
-- vev.notnull: posting_group_id,posting_count
SELECT membership.posting_group_id, count(p.id) AS posting_count
FROM posting_group_postings membership
JOIN postings p ON p.id = membership.posting_id AND p.tenant_id = membership.tenant_id
WHERE membership.tenant_id = :tenantId AND membership.posting_group_id = ANY(:postingGroupIds::int[])
  AND p.account_number = :customerAccountNumber
  AND p.reversed_by_posting_id IS NULL AND p.reverses_posting_id IS NULL
GROUP BY membership.posting_group_id;

-- vev.name: employeePostingGroupCounts :many
-- vev.rows: 100000
-- vev.notnull: posting_group_id,posting_count
SELECT membership.posting_group_id, count(p.id) AS posting_count
FROM posting_group_postings membership
JOIN postings p ON p.id = membership.posting_id AND p.tenant_id = membership.tenant_id
WHERE membership.tenant_id = :tenantId AND membership.posting_group_id = ANY(:postingGroupIds::int[])
  AND p.employee_id IS NOT NULL
GROUP BY membership.posting_group_id;

-- vev.name: postingSettlementDates :many
-- vev.rows: 100000
-- vev.notnull: posting_id,settlement_date
SELECT p.id AS posting_id, min(later.posting_date) AS settlement_date
FROM postings p
JOIN posting_group_postings membership ON membership.posting_id = p.id AND membership.tenant_id = p.tenant_id
JOIN posting_group_postings other ON other.posting_group_id = membership.posting_group_id AND other.tenant_id = p.tenant_id
JOIN postings later ON later.id = other.posting_id AND later.tenant_id = p.tenant_id
WHERE p.tenant_id = :tenantId AND p.account_number = ANY(:accountNumbers::text[])
  AND p.posting_date <= :asOf AND later.posting_date > :asOf
GROUP BY p.id;
