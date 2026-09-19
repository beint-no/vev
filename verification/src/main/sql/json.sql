-- name: hasKey :one
SELECT :document::jsonb ? :key::text AS present;
