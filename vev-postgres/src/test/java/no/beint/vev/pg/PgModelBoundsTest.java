package no.beint.vev.pg;

import no.beint.vev.ModelIdentity;
import no.beint.vev.VevModel;
import no.beint.vev.pg.spi.PgEntityPlan;
import no.beint.vev.pg.spi.PgTenantEntityPlan;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PgModelBoundsTest {
    private static final ModelIdentity IDENTITY = new ModelIdentity(
            "bounded-model",
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    private static final PgColumn ID = new PgColumn(
            "id", PgCodecs.INTEGER, false, PgColumn.Role.ID, 0, 0, 0);
    private static final PgColumn TENANT = new PgColumn(
            "tenant_id", PgCodecs.INTEGER, false, PgColumn.Role.TENANT, 0, 0, 0);

    @Test
    void modelDoesNotTrustCollectionSizeBeforeApplyingItsEntityBound() {
        PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan = plan(List.of(ID, TENANT));
        Collection<PgEntityPlan<TestModel, ?, ?, Integer>> misleading = new AbstractCollection<>() {
            @Override
            public Iterator<PgEntityPlan<TestModel, ?, ?, Integer>> iterator() {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public PgEntityPlan<TestModel, ?, ?, Integer> next() {
                        return plan;
                    }
                };
            }

            @Override
            public int size() {
                return 0;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, misleading));
    }

    @Test
    void planDoesNotTrustListSizeBeforeApplyingItsColumnBound() {
        List<PgColumn> misleading = new AbstractList<>() {
            @Override
            public PgColumn get(int index) {
                return ID;
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        assertThrows(IllegalArgumentException.class, () ->
                new PgModel<>(IDENTITY, List.of(plan(misleading))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void varargsFactoryRejectsOversizedModelsBeforeReadingElements() {
        PgEntityPlan<TestModel, ?, ?, Integer>[] plans =
                (PgEntityPlan<TestModel, ?, ?, Integer>[]) new PgEntityPlan<?, ?, ?, ?>[VevModel.MAXIMUM_ENTITIES + 1];

        assertThrows(IllegalArgumentException.class, () -> PgModel.of(IDENTITY, plans));
    }

    @Test
    void uniqueMetadataCapturesPositionsAndEnforcesDatabaseAndTenantBounds() {
        var positions = new java.util.ArrayList<>(List.of(1, 2));
        var unique = new PgUnique("test_entity_code_key", positions);
        positions.set(0, 0);
        assertEquals(List.of(1, 2), unique.columnIndexes());
        assertThrows(UnsupportedOperationException.class, () -> unique.columnIndexes().set(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgUnique("bad.name", List.of(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> new PgUnique("bad", List.of(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PgUnique("bad", List.of(1, -1)));
        assertThrows(IllegalArgumentException.class, () -> new PgUnique("bad", List.of(1, 64)));
        assertThrows(IllegalArgumentException.class, () -> new PgUnique("bad",
                java.util.stream.IntStream.range(0, 33).boxed().toList()));
        var code = new PgColumn("code", PgCodecs.STRING, true, PgColumn.Role.VALUE, 64, 0, 0);
        for (List<Integer> invalid : List.of(List.of(0, 2), List.of(2, 1), List.of(1, 0, 2), List.of(1, 3))) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT, code), List.of(new PgUnique("bad", invalid))))));
        }
        var largeCode = new PgColumn("code", PgCodecs.STRING, true, PgColumn.Role.VALUE, 1024, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                List.of(plan(List.of(ID, TENANT, largeCode), List.of(unique)))));
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                List.of(plan(List.of(ID, TENANT, code), java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> new PgUnique("key_" + index, List.of(1, 2))).toList()))));
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(List<PgColumn> columns) {
        return plan(columns, List.of());
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(List<PgColumn> columns, List<PgUnique> unique) {
        return plan(columns, unique, no.beint.vev.VevPrimaryKey.Shape.TENANT_ID);
    }

    @Test
    void physicalPrimaryKeysRequireTenantTraversalAndCaptureTheirExactOrder() {
        for (var shape : List.of(no.beint.vev.VevPrimaryKey.Shape.ID, no.beint.vev.VevPrimaryKey.Shape.ID_TENANT)) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT), List.of(), shape))));
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT), List.of(new PgUnique("id_tenant", List.of(0, 1))), shape))));
            var model = new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT), List.of(new PgUnique("tenant_id_key", List.of(1, 0))), shape)));
            assertEquals(shape == no.beint.vev.VevPrimaryKey.Shape.ID ? List.of("id") : List.of("id", "tenant_id"),
                    model.frozenPlans().getFirst().primaryKeyColumns());
        }
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(
            List<PgColumn> columns, List<PgUnique> unique, no.beint.vev.VevPrimaryKey.Shape shape) {
        return plan(columns, unique, shape, List.of());
    }

    @Test
    void capturesCheckMetadataAndRejectsDuplicatesAndHostileUnboundedLists() {
        var checks = new java.util.ArrayList<>(List.of(new PgCheck("test_check", "(id > 0)")));
        var source = plan(List.of(ID, TENANT), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, checks);
        var model = new PgModel<>(IDENTITY, List.of(source));
        checks.clear();
        assertEquals(List.of(new PgCheck("test_check", "(id > 0)")), model.frozenPlans().getFirst().checkConstraints());
        List<PgCheck> misleading = new AbstractList<>() {
            @Override
            public PgCheck get(int index) {
                return new PgCheck("test_check", "true");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };
        for (List<PgCheck> invalid : List.of(misleading, List.of(new PgCheck("duplicate", "true"), new PgCheck("duplicate", "false")),
                java.util.Collections.nCopies(33, new PgCheck("test_check", "true")))) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, invalid))));
        }
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(
            List<PgColumn> columns, List<PgUnique> unique, no.beint.vev.VevPrimaryKey.Shape shape, List<PgCheck> checks) {
        return plan(columns, unique, shape, checks, () -> 1000);
    }

    @Test
    void binaryColumnsRequireExactlyOneMatchingTypedDatabaseBound() {
        var payload = new PgColumn("payload", PgCodecs.BINARY, true, PgColumn.Role.VALUE, 32, 0, 0);
        var bound = PgCheck.binaryMaximum("payload_max", "payload", 32);
        var model = new PgModel<>(IDENTITY, List.of(plan(List.of(ID, TENANT, payload), List.of(),
                no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(bound))));
        assertEquals(List.of(bound), model.frozenPlans().getFirst().checkConstraints());
        assertEquals(-1, payload.expectedTypeModifier());
        payload.validateValue(no.beint.vev.Binary.copyOf(new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> payload.validateValue(no.beint.vev.Binary.copyOf(new byte[33])));
        assertThrows(IllegalArgumentException.class, () -> payload.validateValue(new byte[32]));
        for (List<PgCheck> checks : List.<List<PgCheck>>of(List.of(), List.of(new PgCheck("payload_max", bound.expression())),
                List.of(PgCheck.binaryMaximum("payload_max", "payload", 31)),
                List.of(PgCheck.binaryMaximum("payload_max", "other_column", 32)),
                List.of(bound, PgCheck.binaryMaximum("duplicate_max", "payload", 32)))) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT, payload), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, checks))));
        }
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                List.of(plan(List.of(ID, TENANT), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(bound)))));
        for (var role : List.of(PgColumn.Role.ID, PgColumn.Role.TENANT, PgColumn.Role.VERSION)) {
            assertThrows(IllegalArgumentException.class, () -> new PgColumn("payload", PgCodecs.BINARY, false, role, 32, 0, 0));
        }
        for (int length : List.of(-1, 0, no.beint.vev.Binary.MAXIMUM_LENGTH + 1)) {
            assertThrows(IllegalArgumentException.class, () -> new PgColumn("payload", PgCodecs.BINARY, true, PgColumn.Role.VALUE, length, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> PgCheck.binaryMaximum("payload_max", "payload", length));
        }
        assertThrows(IllegalArgumentException.class, () -> new PgCheck("payload_max", "true", PgCheck.Kind.BINARY_MAXIMUM, "payload", 32));
        assertThrows(IllegalArgumentException.class, () -> new PgCheck("payload_max", "true", PgCheck.Kind.EXACT, "", 32));
        assertThrows(IllegalArgumentException.class, () -> PgCheck.binaryMaximum("payload_max", "bad.name", 32));
        var large = new PgColumn("payload", PgCodecs.BINARY, true, PgColumn.Role.VALUE, 2048, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(plan(List.of(ID, TENANT, large),
                List.of(new PgUnique("payload_key", List.of(1, 2))), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID,
                List.of(PgCheck.binaryMaximum("payload_max", "payload", 2048))))));
    }

    @Test
    void textMetadataSeparatesCharacterBoundsFromBinaryBytesAndVarcharTypeModifiers() {
        var text = new PgColumn("payload", PgCodecs.TEXT, true, PgColumn.Role.VALUE, 32, 0, 0);
        var bound = PgCheck.textMaximum("payload_max", "payload", 32);
        var model = new PgModel<>(IDENTITY, List.of(plan(List.of(ID, TENANT, text), List.of(),
                no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(bound))));
        assertEquals(List.of(bound), model.frozenPlans().getFirst().checkConstraints());
        assertEquals(-1, text.expectedTypeModifier());
        assertEquals(64 + 4 * 32, text.maximumRetainedBytes());
        text.validateValue("🙂".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> text.validateValue("🙂".repeat(33)));
        for (List<PgCheck> checks : List.<List<PgCheck>>of(List.of(), List.of(PgCheck.binaryMaximum("payload_max", "payload", 32)),
                List.of(PgCheck.textMaximum("payload_max", "payload", 31)), List.of(PgCheck.textMaximum("payload_max", "other", 32)),
                List.of(bound, PgCheck.textMaximum("duplicate", "payload", 32)))) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT, text), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, checks))));
        }
        var varchar = new PgColumn("payload", PgCodecs.STRING, true, PgColumn.Role.VALUE, 32, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                List.of(plan(List.of(ID, TENANT, varchar), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(bound)))));
        for (var role : List.of(PgColumn.Role.ID, PgColumn.Role.TENANT, PgColumn.Role.VERSION)) {
            assertThrows(IllegalArgumentException.class, () -> new PgColumn("payload", PgCodecs.TEXT, false, role, 32, 0, 0));
        }
        for (int length : List.of(-1, 0, no.beint.vev.VevText.MAXIMUM_LENGTH + 1)) {
            assertThrows(IllegalArgumentException.class, () -> new PgColumn("payload", PgCodecs.TEXT, true, PgColumn.Role.VALUE, length, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> PgCheck.textMaximum("payload_max", "payload", length));
        }
        assertThrows(IllegalArgumentException.class, () -> new PgCheck("payload_max", bound.expression(), PgCheck.Kind.BINARY_MAXIMUM, "payload", 32));
        var large = new PgColumn("payload", PgCodecs.TEXT, true, PgColumn.Role.VALUE, 1048576, 0, 0);
        var source = plan(List.of(ID, TENANT, large), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID,
                List.of(PgCheck.textMaximum("payload_max", "payload", 1048576)));
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(source)));
    }

    @Test
    void localTimeRequiresExactMicrosecondsWithoutLegacyJdbcCoercion() {
        var time = new PgColumn("clock", PgCodecs.LOCAL_TIME, true, PgColumn.Role.VALUE, 0, 0, 0);
        assertEquals(-1, time.expectedTypeModifier());
        time.validateValue(java.time.LocalTime.MIDNIGHT);
        time.validateValue(java.time.LocalTime.of(23, 59, 59, 999999000));
        time.validateValue(null);
        assertThrows(IllegalArgumentException.class, () -> time.validateValue(java.time.LocalTime.MAX));
        assertThrows(IllegalArgumentException.class, () -> time.validateValue(java.time.LocalTime.ofNanoOfDay(1)));
        assertThrows(IllegalArgumentException.class, () -> time.validateValue(java.sql.Time.valueOf("12:00:00")));
        assertThrows(IllegalArgumentException.class, () -> time.validateValue(java.time.OffsetTime.of(java.time.LocalTime.NOON, java.time.ZoneOffset.UTC)));
    }

    @Test
    void capturesRowLimitsOnceAndKeepsTheMaterializedResultBudget() {
        var bound = new java.util.concurrent.atomic.AtomicInteger(8);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<PgColumn> columns = List.of(ID, TENANT, new PgColumn("body", PgCodecs.STRING, false, PgColumn.Role.VALUE, 65535, 0, 0));
        var source = plan(columns, List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(), () -> {
            calls.incrementAndGet();
            return bound.get();
        });
        var model = new PgModel<>(IDENTITY, List.of(source));
        bound.set(1000);
        var captured = model.frozenPlans().getFirst();
        assertEquals(8, captured.maximumRows());
        captured.requireRowCount(8);
        captured.requireRowCount(0);
        assertThrows(IllegalArgumentException.class, () -> captured.requireRowCount(9));
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(source)));
        for (int invalid : List.of(-1, 0, 1001, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY,
                    List.of(plan(List.of(ID, TENANT), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(), () -> invalid))));
        }
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(
            List<PgColumn> columns, List<PgUnique> unique, no.beint.vev.VevPrimaryKey.Shape shape, List<PgCheck> checks,
            java.util.function.IntSupplier maximumRows) {
        return plan(columns, unique, shape, checks, maximumRows, () -> PgEntityPlan.ABI_VERSION);
    }

    @Test
    void rejectsUnversionedAndIncompatiblePlansBeforeCapturingOtherMetadata() {
        for (Integer abi : java.util.Arrays.asList(null, -1, 0, 1, PgEntityPlan.ABI_VERSION + 1, Integer.MAX_VALUE)) {
            var source = plan(null, null, null, null, () -> {
                throw new AssertionError("Incompatible plans must fail before metadata access");
            }, abi == null ? null : () -> abi);
            var failure = assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(source)));
            assertEquals("Generated PostgreSQL plan ABI mismatch: runtime requires " + PgEntityPlan.ABI_VERSION
                    + ", plan declares " + (abi == null ? 0 : abi)
                    + "; recompile mappings with matching Vev processor/runtime versions", failure.getMessage());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyMetadataCannotAcquireMutationCapabilitiesOrCompileWriteStatements() {
        var source = plan(List.of(ID, TENANT));
        for (Class<?> extra : List.of(no.beint.vev.AssignedEntityType.class, no.beint.vev.VersionedEntityType.class,
                no.beint.vev.pg.spi.PgGeneratedEntityPlan.class, no.beint.vev.DeletableEntityType.class)) {
            var invalid = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{no.beint.vev.pg.spi.PgReadOnlyEntityPlan.class, PgTenantEntityPlan.class, extra},
                    (proxy, method, arguments) -> method.getName().equals("creationType") ? Object.class : method.invoke(source, arguments));
            var failure = assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(invalid)));
            assertEquals("Read-only plans cannot expose mutation capabilities", failure.getMessage());
        }
        for (Class<?> extra : List.of(PgEntityPlan.class, no.beint.vev.pg.spi.PgIdentityEntityPlan.class)) {
            var valid = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{no.beint.vev.pg.spi.PgReadOnlyEntityPlan.class, PgTenantEntityPlan.class, extra},
                    (proxy, method, arguments) -> method.invoke(source, arguments));
            var captured = new PgModel<>(IDENTITY, List.of(valid)).frozenPlans().getFirst();
            org.junit.jupiter.api.Assertions.assertTrue(captured.readOnly());
            assertEquals(extra == no.beint.vev.pg.spi.PgIdentityEntityPlan.class, captured.generatedIdentity());
            org.junit.jupiter.api.Assertions.assertNull(captured.creationType());
            org.junit.jupiter.api.Assertions.assertNull(captured.sql().insert());
            org.junit.jupiter.api.Assertions.assertNull(captured.sql().insertMultiple());
            org.junit.jupiter.api.Assertions.assertNull(captured.sql().update());
            org.junit.jupiter.api.Assertions.assertNull(captured.sql().updateMultiple());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletionMetadataRequiresBothGeneratedIdentityAndVersionedSpiCapabilities() {
        var source = plan(List.of(ID, TENANT));
        for (Class<?> extra : List.of(PgEntityPlan.class, no.beint.vev.pg.spi.PgGeneratedEntityPlan.class,
                no.beint.vev.pg.spi.PgVersionedEntityPlan.class)) {
            var inconsistent = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{extra, PgTenantEntityPlan.class, no.beint.vev.DeletableEntityType.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("creationType")) return Object.class;
                        if (method.getName().equals("columns")) throw new AssertionError("Invalid capabilities must fail before column capture");
                        return method.invoke(source, arguments);
                    });
            var failure = assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(inconsistent)));
            assertEquals("Physical deletion requires a versioned generated identity plan", failure.getMessage());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commonOrReadOnlyMetadataDoesNotImplicitlyGrantTenantOwnership() {
        var source = plan(List.of(ID, TENANT));
        for (Class<?> kind : List.of(PgEntityPlan.class, no.beint.vev.pg.spi.PgReadOnlyEntityPlan.class,
                no.beint.vev.pg.spi.PgIdentityEntityPlan.class)) {
            var missing = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{kind}, (proxy, method, arguments) -> {
                        if (method.getName().equals("columns")) throw new AssertionError("Ownership must be explicit before column capture");
                        return method.invoke(source, arguments);
                    });
            var failure = assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(missing)));
            assertEquals("A PostgreSQL entity plan must explicitly declare tenant ownership or shared read-only access", failure.getMessage());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedCapabilitiesRejectTenantRolesMutationMarkersAndMissingTransactionAuthority() {
        var source = plan(List.of(ID));
        for (String variant : List.of("tenantMarker", "tenantColumn", "tenantPrimaryKey", "assigned", "generated", "versioned", "deleted", "onlyShared")) {
            var kinds = new java.util.ArrayList<Class<?>>();
            kinds.add(no.beint.vev.pg.spi.PgSharedEntityPlan.class);
            switch (variant) {
                case "tenantMarker" -> kinds.add(PgTenantEntityPlan.class);
                case "assigned" -> kinds.add(no.beint.vev.AssignedEntityType.class);
                case "generated" -> kinds.add(no.beint.vev.pg.spi.PgGeneratedEntityPlan.class);
                case "versioned" -> kinds.add(no.beint.vev.VersionedEntityType.class);
                case "deleted" -> kinds.add(no.beint.vev.DeletableEntityType.class);
                default -> { }
            }
            var invalid = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), kinds.toArray(Class<?>[]::new), (proxy, method, arguments) -> switch (method.getName()) {
                        case "tenantCodec", "tenantColumn", "tenantKeyOf" -> throw new AssertionError("Shared plans have no tenant metadata");
                        case "primaryKeyShape" -> variant.equals("tenantPrimaryKey") ? no.beint.vev.VevPrimaryKey.Shape.TENANT_ID : no.beint.vev.VevPrimaryKey.Shape.ID;
                        case "columns" -> variant.equals("tenantColumn") ? List.of(ID, TENANT) : List.of(ID);
                        default -> method.invoke(source, arguments);
                    });
            var failure = assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(invalid)), variant);
            String expected = switch (variant) {
                case "tenantMarker", "generated" -> "mutually exclusive";
                case "tenantColumn", "tenantPrimaryKey" -> "no tenant column and an ID-only primary key";
                case "onlyShared" -> "at least one tenant-owned mapping";
                default -> "cannot expose mutation capabilities";
            };
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturesTenantMetadataOnceAndRetainsDirectSnapshotAccess() {
        var source = plan(List.of(ID, TENANT));
        var codecCalls = new java.util.concurrent.atomic.AtomicInteger();
        var columnCalls = new java.util.concurrent.atomic.AtomicInteger();
        var keyCalls = new java.util.concurrent.atomic.AtomicInteger();
        var counted = (PgEntityPlan<TestModel, TestEntity, Integer, Integer>) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PgTenantEntityPlan.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("tenantCodec") && codecCalls.incrementAndGet() != 1) {
                        throw new AssertionError("Tenant codec must be captured once");
                    }
                    if (method.getName().equals("tenantColumn") && columnCalls.incrementAndGet() != 1) {
                        throw new AssertionError("Tenant column must be captured once");
                    }
                    if (method.getName().equals("tenantKeyOf")) keyCalls.incrementAndGet();
                    return method.invoke(source, arguments);
                });
        var model = new PgModel<>(IDENTITY, List.of(counted));
        var captured = model.frozenPlan(counted);
        for (int iteration = 0; iteration < 3; iteration++) {
            assertEquals(PgCodecs.INTEGER, captured.tenantCodec());
            assertEquals("tenant_id", captured.tenantColumn());
            assertEquals(7, captured.tenantKeyOf(new TestEntity(11, 7)));
        }
        assertEquals(1, codecCalls.get());
        assertEquals(1, columnCalls.get());
        assertEquals(3, keyCalls.get());
    }

    @Test
    void capturesGeneratedPlanAbiOnceDuringModelConstruction() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var source = plan(List.of(ID, TENANT), List.of(), no.beint.vev.VevPrimaryKey.Shape.TENANT_ID, List.of(),
                () -> 1000, () -> calls.incrementAndGet() == 1 ? PgEntityPlan.ABI_VERSION : 0);
        var model = new PgModel<>(IDENTITY, List.of(source));
        model.frozenPlans().getFirst().requireRowCount(1);
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> new PgModel<>(IDENTITY, List.of(source)));
        assertEquals(2, calls.get());
    }

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(
            List<PgColumn> columns, List<PgUnique> unique, no.beint.vev.VevPrimaryKey.Shape shape, List<PgCheck> checks,
            java.util.function.IntSupplier maximumRows, java.util.function.IntSupplier abi) {
        return new PgTenantEntityPlan<>() {
            @Override
            public int generatedPlanAbi() {
                return abi == null ? PgTenantEntityPlan.super.generatedPlanAbi() : abi.getAsInt();
            }

            @Override
            public int maximumRows() {
                return maximumRows.getAsInt();
            }

            @Override
            public no.beint.vev.VevPrimaryKey.Shape primaryKeyShape() {
                return shape;
            }

            @Override
            public Class<TestEntity> javaType() {
                if (columns == null) throw new AssertionError("Incompatible plans must fail before any metadata access");
                return TestEntity.class;
            }

            @Override
            public Class<Integer> keyType() {
                return Integer.class;
            }

            @Override
            public String logicalName() {
                return TestEntity.class.getName();
            }

            @Override
            public ModelIdentity modelIdentity() {
                return IDENTITY;
            }

            @Override
            public PgCodec<Integer> keyCodec() {
                return PgCodecs.INTEGER;
            }

            @Override
            public PgCodec<Integer> tenantCodec() {
                return PgCodecs.INTEGER;
            }

            @Override
            public String schemaName() {
                return "bounded";
            }

            @Override
            public String tableName() {
                return "test_entity";
            }

            @Override
            public String tenantColumn() {
                return "tenant_id";
            }

            @Override
            public List<PgColumn> columns() {
                return columns;
            }

            @Override
            public List<PgIndex<TestModel, TestEntity, Integer, ?>> indexes() {
                return List.of();
            }

            @Override
            public List<PgUnique> uniqueConstraints() {
                return unique;
            }

            @Override
            public List<PgCheck> checkConstraints() {
                return checks;
            }

            @Override
            public Object columnValue(TestEntity entity, int columnIndex) {
                return columnIndex == 0 ? entity.id() : entity.tenantId();
            }

            @Override
            public TestEntity readRow(java.sql.ResultSet resultSet, int firstColumn) {
                throw new UnsupportedOperationException("Metadata-only fixture has no JDBC hydration path");
            }

            @Override
            public Integer keyOf(TestEntity entity) {
                return entity.id();
            }

            @Override
            public Integer tenantKeyOf(TestEntity entity) {
                return entity.tenantId();
            }
        };
    }

    private static final class TestModel {
        private TestModel() {
        }
    }

    private record TestEntity(Integer id, Integer tenantId) {
    }
}
