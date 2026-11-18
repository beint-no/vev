-- vev.name: postingSettlementDates :many
-- vev.notnull: posting_id,settlement_date
SELECT p.id AS posting_id, min(later.posting_date) AS settlement_date
FROM postings p
JOIN posting_group_postings membership ON membership.posting_id = p.id AND membership.tenant_id = p.tenant_id
JOIN posting_group_postings other ON other.posting_group_id = membership.posting_group_id AND other.tenant_id = p.tenant_id
JOIN postings later ON later.id = other.posting_id AND later.tenant_id = p.tenant_id
WHERE p.tenant_id = :tenantId AND p.account_number = ANY(:accountNumbers::text[])
  AND p.posting_date <= :asOf AND later.posting_date > :asOf
GROUP BY p.id;
