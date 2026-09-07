# Kotlin record mappings

Vev can compile separately built Kotlin `@JvmRecord` data classes into the same
typed PostgreSQL plans used for Java records. Kotlin `2.4.20` with its normal
parameter null checks is verified on JDK `27+35-2325`. This is immutable snapshot
mapping, not support for mutable JPA classes, managed relationships, or dirty
checking. Jakarta still forbids records as provider-managed entities.

Use explicit field annotations and keep Kotlin nullability aligned with the
database contract:

```kotlin
@Entity
@Table(name = "entry", schema = "ledger")
@JvmRecord
data class Entry(
    @field:Id @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:VevIndex(name = "entry_label_idx")
    @field:Column(name = "label", nullable = false, length = 128) val label: String,
    @field:Column(name = "alias", nullable = true, length = 64) val alias: String?
)
```

The processor checks the Kotlin field/accessor nullability annotations against
the explicit column declaration. Both `String` mapped nullable and `String?`
mapped required are errors. Primitive components remain non-nullable. The
existing enum, reference, unique-constraint, scalar-bound, and tenant rules apply.
See Kotlin's [record documentation](https://kotlinlang.org/docs/jvm-records.html)
for language requirements and annotation use-site targets.

Compile records before processing the Java `@VevModel` declaration. A dedicated
record module can depend on `vev-core` and Jakarta Persistence metadata; an
application module then depends on those compiled records, `vev-postgres`, and
the `vev-processor` annotation processor. The registry lists the Kotlin classes
explicitly. No KAPT, KSP, runtime scanning, reflective hydration, or Hibernate metadata is
required. The unpublished `vev-kotlin-test-model` and `vev-integration-tests`
modules demonstrate this dependency order.

The Kotlin 2.4.20 fixture targets JVM 26 bytecode. Use JDK 27 for compilation
and execution, select `JvmTarget.JVM_26` for the record module, and align that
module's Java compiler release to 26. Its Gradle API/runtime attributes must still
declare JVM 27 because Vev requires that runtime. The fixture build shows the
exact configuration. The fixture bytecode target does not imply JDK 26 support;
Vev's own published modules and processor target JDK 27.

The build-time verifier permits Kotlin's standard parameter-null-check prelude
only for mapped non-null reference components. It validates the loaded argument,
parameter name, and exact call. It also parses the selected Kotlin `Intrinsics`
class: no class initializer or custom superclass/interface may execute, and the
non-null branch of `checkNotNullParameter` must return directly. It checks this
callee once per compiler instance without loading or executing Kotlin classes.
Vev validates every mapped scalar before canonical construction, so that is the
branch used for hydration. Normal Kotlin null guards remain enabled for direct
application construction too.

The canonical constructor must otherwise initialize `Record` and assign each
unmodified argument directly. Transformations, validation with other calls,
side effects, and nontrivial `init` blocks fail compilation. Accessors must return
their fields directly. Extra mutable state, companion-object initialization, and
interfaces remain unsupported. `copy`, destructuring, equality, rendering, and
ordinary helpers are application operations; Vev does not invoke them. A `copy`
call creates another detached value and never schedules a database update.

Kotlin tooling and its standard library are verification-fixture dependencies.
They are locked and checksum-verified, and are absent from Vev's published
runtime and processor dependency graphs. Supporting another compiler's output
requires rerunning the positive, negative, and PostgreSQL fixtures; unknown
constructor or null-check bytecode is rejected.
