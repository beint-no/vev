# Local time values

`java.time.LocalTime` maps to PostgreSQL `time without time zone` through the
generated `PgCodecs.LOCAL_TIME`. No temporal annotation, timezone conversion,
legacy JDBC value, or custom converter is required:

```java
@VevIndex(name = "event_clock_idx")
@Column(name = "observed_at", nullable = true)
LocalTime observedAt
```

Values range from `00:00:00` through `23:59:59.999999` at exact microsecond
precision. Finer fractions fail before SQL, including equality-query parameters;
Vev does not silently round them. The column must use the default PostgreSQL time
precision, with no type modifier. A narrower database precision fails startup
verification. Explicit `@Column.secondPrecision` overrides remain unsupported in
this profile, as do `java.sql.Time`, `OffsetTime`, and `@Temporal`.

PostgreSQL also permits `24:00:00`, which Java LocalTime cannot represent exactly.
pgjdbc maps it to `LocalTime.MAX`; Vev rejects that sentinel through its exact
microsecond check. Reading it poisons the transaction and rolls back earlier
writes, even when the caller catches the read failure. This is tested in both
text transfer and forced binary transfer. The mapping does not automatically
install a database constraint excluding that value: applications must inspect
existing rows and preserve any domain rule concerning end-of-day values.
See the [PostgreSQL time range](https://www.postgresql.org/docs/18/datatype-datetime.html)
and [pgjdbc conversion implementation](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.13/pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java).

The scalar supports explicit nullability, Java and compiled Kotlin records,
assigned/identity writes, ordered batches, versioned updates, typed equality and
null indexes, and tenant-scoped unique constraints under the existing key budget.
It is not an identifier, tenant-key, or version type. Named checks may compare
time values through approved builtin PostgreSQL comparison functions. Timezone
arithmetic and implicit conversions remain outside that check contract.
