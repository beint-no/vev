-- vev.name: supplierPostingGroupCounts :many
-- vev.notnull: posting_group_id,posting_count
SELECT membership.posting_group_id, count(p.id) AS posting_count
FROM supplier_posting_group_memberships membership
JOIN postings p ON p.id = membership.posting_id AND p.tenant_id = membership.tenant_id
WHERE membership.tenant_id = :tenantId AND membership.posting_group_id = ANY(:postingGroupIds::int[])
  AND p.account_number = :supplierAccountNumber
GROUP BY membership.posting_group_id;
