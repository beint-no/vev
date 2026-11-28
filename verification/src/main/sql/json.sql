-- vev.name: hasKey :one
-- vev.scope: global
SELECT :document::jsonb ? :key::text AS present;
