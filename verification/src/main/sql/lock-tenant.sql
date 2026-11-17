-- name: lockTenant :void
SELECT pg_advisory_xact_lock(hashtextextended('tenant:' || :tenantId::int || ':' || :reference, 0));
