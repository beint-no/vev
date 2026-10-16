-- name: lockTenant :void
-- tenant: tenantId
SELECT pg_advisory_xact_lock(hashtextextended('tenant:' || :tenantId::int || ':' || :reference, 0));
