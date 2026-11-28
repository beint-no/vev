-- vev.name: streamSeries :stream
-- vev.scope: global
-- vev.notnull: n
SELECT n FROM generate_series(1, :count::int) AS n;
