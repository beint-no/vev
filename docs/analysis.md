# Design

Vev owns a narrow path: PostgreSQL validates SQL at build time, the Kotlin compiler
produces typed JVM APIs, and generated code binds and maps JDBC values at runtime.

## SQL files and Kotlin strings

Both can produce the same prepared statement and runtime performance. Files separate
SQL changes from Kotlin compilation inputs, work with SQL editors and database tools,
and avoid a Kotlin parser/compiler plugin just to locate and evaluate query strings.
Multiline strings would need a restricted constant-only API plus integration into the
Kotlin compiler to validate SQL and expose generated result types during compilation.
Runtime strings alone cannot provide that contract.

Git stores changes as deltas; file layout is primarily an editing and ownership choice,
not a meaningful repository-size optimization. Vev uses one query per source file, named after its generated method. This gives agents
a small, complete unit for discovery, context loading, validation and review. Related
queries belong in directories. Per-query generated implementations and a stable facade
keep SQL-only edits local.

Comment directives carry the API information PostgreSQL cannot infer: method name,
cardinality, input nullability, global scope and mapping overrides. The `vev.` namespace
makes their role explicit and catches unknown directives. They live with the query rather
than in a separately synchronized configuration. They are not optimizer hints or runtime
instructions, and one query's metadata never carries into the next.

SQL text includes and parameterized fragments are deliberately absent. Reusing a filter
or join changes the meaning of every consumer and can hide tenant or aggregate semantics.
Prefer a cohesive query with visible predicates. Use database views/functions when shared
semantics have a clear owner, migration lifecycle and execution tests.

## Generated Kotlin

Kotlin source generation preserves data classes, named arguments, nullable types, value
classes and compiler-maintained metadata. The compiler checks the JVM parameter-slot
limit before emitting a row: nullable/reference columns use one slot, required Long/Double
columns use two, and Kotlin’s generated copy method also needs masks and receiver slots.
The generated binding lambda also captures query arguments and the tenant value. The
compiler budgets the method-handle receiver and binding argument so oversized inputs fail
before Kotlin emits classes that cannot load. Arrays keep list filters within one bind.
Use narrow report projections rather than flattening an entire entity graph into one row.
Static JVM methods also support Java callers.
A direct-bytecode backend could remove generated-source compilation, but would have to
maintain that Kotlin ABI itself. Build measurements guide that decision; equivalent
bytecode would not inherently make JDBC execution faster.

## Tenant and transaction scope

A private ScopedValue resolves a lexical, thread-confined JDBC session. Tenant queries
bind the scope's required integer identifier; SQL retains explicit tenant predicates and
joins. Composite foreign keys enforce tenant consistency on references.
The compiler checks the bind contract, not whether a predicate expresses authorization.

Owned scopes commit or roll back. Borrowed scopes preserve a caller's transaction boundary.
There is no persistence context, cascade engine, SQL DSL or framework adapter. During
incremental adoption the application owns transaction integration and flushes pending
entity changes before executing SQL on the shared connection.

## Adoption

Start with projections and reports, then migrate bounded writes with deliberate choices
for optimistic locking, cascades, auditing and transaction propagation. Generated code
removes reflective mapping and entity tracking; SQL plans, statement counts and indexes
still dominate database work. The small core and absent ORM metadata reduce the work a
fully migrated application must load, but an application retaining Hibernate retains that
cost. See [measured comparisons](performance.md).

A write needs an explicit SQL contract. `INSERT … RETURNING` reads identity, default
and trigger-produced values. A tenant-keyed `ON CONFLICT … DO UPDATE … RETURNING`
replaces find-then-save without its first-insert race. An optimistic update includes
its expected version in `WHERE`; a missing `:optional` result lets the caller choose
its conflict policy. SQL constraints and affected-row counts remain visible.

Migrate a complete small data owner, such as tenant project settings, so its rows
have one persistence path. Removing that owner's entity also removes stale managed
copies after SQL writes. Borrowed scopes participate in the caller's transaction;
the application enforces framework read-only hints before issuing writes. Returned
numeric values have PostgreSQL's stored scale, rather than an unsaved input's scale.
