-- name: hasKey :one
-- scope: global
SELECT :document::jsonb ? :key::text AS present;
