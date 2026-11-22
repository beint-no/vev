-- vev.name: customPlanSeries :many
-- vev.scope: global
-- vev.plan: custom
-- vev.nullable: minimum
SELECT value FROM generate_series(1, 20) AS value WHERE (:minimum::int IS NULL OR value >= :minimum::int);
