package no.beint.vev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CoreValuesTest {
    private static final ModelIdentity MODEL = new ModelIdentity(
            "test", "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    private static final VersionedEntityType<TestModel, TestEntity, Long, Integer> ENTITY = new TestEntityType();
    private static final EntityType<TestModel, TestEntity, String> STRING_ENTITY = new StringEntityType();

    @Test
    void batchIsBoundedAndDetachedFromItsInput() {
        List<Integer> mutable = new ArrayList<>(List.of(1, 2));
        Batch<Integer> batch = Batch.copyOf(mutable);
        mutable.add(3);

        assertEquals(List.of(1, 2), batch.values());
        assertThrows(UnsupportedOperationException.class, () -> batch.values().add(4));
        assertThrows(IllegalArgumentException.class, () ->
                Batch.copyOf(java.util.Collections.nCopies(Batch.MAX_SIZE + 1, 1)));
    }

    @Test
    void batchDoesNotTrustACollectionSizeToEnforceItsBound() {
        Collection<Integer> misleading = new AbstractCollection<>() {
            @Override
            public Iterator<Integer> iterator() {
                return java.util.stream.Stream.iterate(0, value -> value + 1).iterator();
            }

            @Override
            public int size() {
                return 0;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> Batch.copyOf(misleading));
    }

    @Test
    void rowsCannotClaimMoreDataBeforeFillingTheirLimit() {
        QueryLimit limit = new QueryLimit(3);

        assertThrows(IllegalArgumentException.class, () -> new Rows<>(List.of(1, 2), limit, true));
        assertThrows(IllegalArgumentException.class, () -> new Rows<>(List.of(1, 2, 3, 4), limit, false));
        Rows<Integer> completePage = new Rows<>(List.of(1, 2, 3), limit, true);

        assertTrue(completePage.hasMore());
    }

    @Test
    void rowsDoNotTrustAListSizeToEnforceTheirBound() {
        List<Integer> misleading = new java.util.AbstractList<>() {
            @Override
            public Integer get(int index) {
                return index;
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        assertThrows(IllegalArgumentException.class, () ->
                new Rows<>(misleading, new QueryLimit(3), false));
    }

    @Test
    void entityKeysValidateRuntimeTypeErasureBoundaries() {
        EntityKey<TestModel, TestEntity, Long> key = ENTITY.key(7L);

        assertEquals(7L, key.value());
        assertThrows(IllegalArgumentException.class, () -> rawKey(ENTITY, "wrong"));
    }

    @Test
    void tenantAndModelIdentifiersRejectAmbiguousText() {
        TenantAuthority<TestModel, String> textAuthority = boundAuthority(String.class);
        TenantAuthority<TestModel, Integer> integerAuthority = boundAuthority(Integer.class);

        assertThrows(IllegalArgumentException.class, () -> textAuthority.scope(" tenant"));
        assertThrows(IllegalArgumentException.class, () -> textAuthority.scope("\n"));
        assertEquals(42, integerAuthority.scope(42).tenantId());
        assertThrows(IllegalArgumentException.class, () -> rawTenantScope(Integer.class, "wrong"));
        assertThrows(IllegalArgumentException.class, () ->
                TenantAuthority.create(TestModel.class, MODEL, int.class));
        assertThrows(IllegalArgumentException.class, () -> new ModelIdentity("test", " "));
    }

    @Test
    void tenantScopesAreCapabilitiesOwnedByOneExactAuthority() {
        TenantAuthority<TestModel, Integer> authority =
                TenantAuthority.create(TestModel.class, MODEL, Integer.class);
        TenantAuthority<TestModel, Integer> otherAuthority =
                TenantAuthority.create(TestModel.class, MODEL, Integer.class);
        TenantAuthority.Claim<TestModel> claim = claim(authority);
        TenantAuthority.Claim<TestModel> otherClaim = claim(otherAuthority);
        TenantScope<TestModel, Integer> scope = authority.scope(42);

        assertSame(scope, authority.requireScope(scope, claim));
        assertThrows(IllegalArgumentException.class, () -> otherAuthority.requireScope(scope, otherClaim));
        assertThrows(IllegalStateException.class, () -> authority.reserve(MODEL));
    }

    @Test
    void failedRuntimeReservationDoesNotConsumeTenantAuthority() {
        TenantAuthority<TestModel, Integer> authority =
                TenantAuthority.create(TestModel.class, MODEL, Integer.class);

        assertThrows(IllegalStateException.class, () -> authority.scope(42));
        TenantAuthority.Reservation<TestModel> reservation = authority.reserve(MODEL);
        try {
            assertThrows(IllegalStateException.class, () -> authority.scope(42));
        } finally {
            reservation.close();
        }

        TenantAuthority.Claim<TestModel> claim = claim(authority);
        TenantScope<TestModel, Integer> scope = authority.scope(42);
        assertSame(scope, authority.requireScope(scope, claim));
    }

    @Test
    void stringEntityKeysRejectAmbiguousOrUnboundedText() {
        assertEquals("stable", STRING_ENTITY.key("stable").value());
        assertEquals("😀".repeat(128), STRING_ENTITY.key("😀".repeat(128)).value());
        assertThrows(IllegalArgumentException.class, () -> STRING_ENTITY.key(" key"));
        assertThrows(IllegalArgumentException.class, () -> STRING_ENTITY.key("x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> STRING_ENTITY.key("😀".repeat(129)));
        assertThrows(IllegalArgumentException.class, () ->
                STRING_ENTITY.key(String.valueOf(Character.MIN_HIGH_SURROGATE)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityKey<?, ?, ?> rawKey(EntityType<?, ?, ?> entityType, Object value) {
        return new EntityKey(entityType, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TenantScope<?, ?> rawTenantScope(Class<?> type, Object value) {
        TenantAuthority authority = boundAuthority(type);
        return authority.scope(value);
    }

    private static <T> TenantAuthority<TestModel, T> boundAuthority(Class<T> tenantType) {
        TenantAuthority<TestModel, T> authority = TenantAuthority.create(TestModel.class, MODEL, tenantType);
        claim(authority);
        return authority;
    }

    private static <T> TenantAuthority.Claim<TestModel> claim(TenantAuthority<TestModel, T> authority) {
        try (TenantAuthority.Reservation<TestModel> reservation = authority.reserve(MODEL)) {
            return reservation.claim();
        }
    }

    private record TestEntity(long id, int version) {
    }

    private static final class TestModel {
        private TestModel() {
        }
    }

    private static final class TestEntityType implements VersionedEntityType<TestModel, TestEntity, Long, Integer> {
        @Override
        public Class<TestEntity> javaType() {
            return TestEntity.class;
        }

        @Override
        public Class<Long> keyType() {
            return Long.class;
        }

        @Override
        public String logicalName() {
            return "test_entity";
        }

        @Override
        public ModelIdentity modelIdentity() {
            return MODEL;
        }

        @Override
        public Class<Integer> versionType() {
            return Integer.class;
        }
    }

    private static final class StringEntityType implements EntityType<TestModel, TestEntity, String> {
        @Override
        public Class<TestEntity> javaType() {
            return TestEntity.class;
        }

        @Override
        public Class<String> keyType() {
            return String.class;
        }

        @Override
        public String logicalName() {
            return "string_test_entity";
        }

        @Override
        public ModelIdentity modelIdentity() {
            return MODEL;
        }
    }
}
