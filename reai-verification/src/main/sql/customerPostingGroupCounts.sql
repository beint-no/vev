-- vev.name: customerPostingGroupCounts :many
-- vev.notnull: posting_group_id,posting_count
SELECT membership.posting_group_id, count(p.id) AS posting_count
FROM posting_group_postings membership
JOIN postings p ON p.id = membership.posting_id AND p.tenant_id = membership.tenant_id
WHERE membership.tenant_id = :tenantId AND membership.posting_group_id = ANY(:postingGroupIds::int[])
  AND p.account_number = :customerAccountNumber
  AND p.reversed_by_posting_id IS NULL AND p.reverses_posting_id IS NULL
GROUP BY membership.posting_group_id;
