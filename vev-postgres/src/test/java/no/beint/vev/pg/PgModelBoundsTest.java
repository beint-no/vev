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

    private static PgEntityPlan<TestModel, TestEntity, Integer, Integer> plan(List<PgColumn> columns) {
        return new PgEntityPlan<>() {
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
