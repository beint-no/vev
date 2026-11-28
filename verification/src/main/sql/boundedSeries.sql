-- vev.name: boundedSeries :many
-- vev.scope: global
SELECT generate_series(1, :number::int) AS value;
