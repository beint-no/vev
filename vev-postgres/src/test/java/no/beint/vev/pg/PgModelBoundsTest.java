package no.beint.vev.pg;

import no.beint.vev.ModelIdentity;
import no.beint.vev.VevModel;
import no.beint.vev.pg.spi.PgEntityPlan;
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
        return new PgEntityPlan<>() {
            @Override
            public no.beint.vev.VevPrimaryKey.Shape primaryKeyShape() {
                return shape;
            }

            @Override
            public Class<TestEntity> javaType() {
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
            public TestEntity instantiate(Object[] columnValues) {
                return new TestEntity((Integer) columnValues[0], (Integer) columnValues[1]);
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
