-- vev.name: cappedSeries :many
-- vev.scope: global
-- vev.rows: 1000
SELECT generate_series(1, :number::int) AS value;
