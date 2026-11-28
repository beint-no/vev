-- vev.name: arrayProjection :one
-- vev.scope: global
SELECT ARRAY[1, 2] AS ids, count(*) AS total, NULL::bigint AS missing
