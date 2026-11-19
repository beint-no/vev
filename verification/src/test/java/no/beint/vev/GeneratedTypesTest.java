package no.beint.vev;

import no.beint.vev.compiler.Postgres;
import no.beint.vev.fixture.Queries;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedTypesTest {
    private Postgres postgres;
    private Vev vev;
    private static final UUID UUID_VALUE = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final List<Object> VALUES = List.of(
        true, (short) -32768, Integer.MIN_VALUE, Long.MAX_VALUE, 1.25f, -9.125d, new BigDecimal("12345678901234567890.123456789"),
        "æøå 日本語 😀 ' : ? \\ $", "varchar", "x", "name", "MiXeD@EXAMPLE.INVALID", "<root>æøå</root>", "10.2.3.4", UUID_VALUE,
        LocalDate.of(2000,2,29), LocalTime.of(23,59,59,123456000), LocalDateTime.of(2026,11,17,12,30,45,123456000),
        Instant.parse("2026-11-17T12:30:45.123456Z"), new byte[]{0,-1,42}, "{\"x\":1}", "{\"x\": 1}",
        Arrays.asList(true,null,false), Arrays.asList((short)1,null,(short)-2), Arrays.asList(1,null,-2), Arrays.asList(Long.MAX_VALUE,null,0L),
        Arrays.asList(1.5f,null,-2.5f), Arrays.asList(1.5d,null,-2.5d), Arrays.asList(BigDecimal.ONE,null,BigDecimal.TEN),
        Arrays.asList("æøå",null,""), Arrays.asList("x",null,"y"), Arrays.asList("x",null,"y"), Arrays.asList(UUID_VALUE,null,UUID_VALUE),
        Arrays.asList(LocalDate.of(2000,2,29),null,LocalDate.of(2026,11,19)),
        Arrays.asList(LocalTime.of(23,59,59,123456000),null,LocalTime.MIDNIGHT),
        Arrays.asList(LocalDateTime.of(2026,11,17,12,30,45,123456000),null,LocalDateTime.of(1970,1,1,0,0)),
        Arrays.asList(Instant.parse("2026-11-17T12:30:45.123456Z"),null,Instant.EPOCH),
        Arrays.asList("{\"a\":1}",null,"[1,2]"),
        Arrays.asList("{\"a\": 1}",null,"[1, 2]")
    );
    @BeforeAll void start() throws Exception {
        postgres = new Postgres(""); postgres.migrate(Path.of("src/main/schema"));
        var source = new PGSimpleDataSource(); source.setURL(postgres.url()); source.setUser(postgres.user()); source.setPassword(postgres.password());
        vev = new Vev(source);
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }
    private Object roundTrip(int index, Object input) {
        try {
            var method = Arrays.stream(Queries.class.getMethods()).filter(m -> m.getName().equals("typeCase" + index)).findFirst().orElseThrow();
            return vev.readGlobal(session -> {
                try { var row = method.invoke(null, input); return row.getClass().getMethod("getValue").invoke(row); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Generated reader failed", failure); }
            });
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }
    @TestFactory Stream<DynamicTest> allGeneratedBindingsAndReadersRoundTrip() {
        return IntStream.range(0,VALUES.size()).mapToObj(i -> DynamicTest.dynamicTest("type " + i + " round trip", () -> {
            Object expected = VALUES.get(i), actual = roundTrip(i, expected);
            if (expected instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) actual);
            else assertEquals(expected, actual);
        }));
    }
    @TestFactory Stream<DynamicTest> nullableBindingsDoNotBecomePrimitiveDefaults() {
        return IntStream.range(0,VALUES.size()).mapToObj(i -> DynamicTest.dynamicTest("type " + i + " NULL", () -> assertNull(roundTrip(i, null))));
    }
    @TestFactory Stream<DynamicTest> arrayEmptyAndNullElementsRemainDistinct() {
        return IntStream.range(22,VALUES.size()).boxed().flatMap(i -> Stream.of(
            DynamicTest.dynamicTest("array " + i + " empty", () -> assertEquals(List.of(), roundTrip(i, List.of()))),
            DynamicTest.dynamicTest("array " + i + " null element", () -> assertEquals(Arrays.asList((Object)null), roundTrip(i, Arrays.asList((Object)null))))));
    }
    record Edge(int type, Object value) {}
    @TestFactory Stream<DynamicTest> scalarBoundaryValuesRetainTheirMeaning() {
        return Stream.of(
            new Edge(0,false),new Edge(1,Short.MAX_VALUE),new Edge(2,Integer.MAX_VALUE),new Edge(3,Long.MIN_VALUE),
            new Edge(4,Float.NaN),new Edge(4,Float.POSITIVE_INFINITY),new Edge(4,Float.NEGATIVE_INFINITY),
            new Edge(5,Double.NaN),new Edge(5,Double.POSITIVE_INFINITY),new Edge(5,Double.NEGATIVE_INFINITY),
            new Edge(6,new BigDecimal("0.000000000000000000001")),new Edge(6,new BigDecimal("-999999999999999999999999.12345")),
            new Edge(7,""),new Edge(7,"'; DROP TABLE customer; --"),new Edge(7,"\uD83D\uDE00".repeat(10000)),
            new Edge(15,LocalDate.of(1,1,1)),new Edge(15,LocalDate.of(9999,12,31)),new Edge(16,LocalTime.MIDNIGHT),
            new Edge(19,new byte[0])
        ).map(edge->DynamicTest.dynamicTest("boundary type "+edge.type+" "+edge.value.getClass().getSimpleName(),()->{
            Object actual=roundTrip(edge.type,edge.value);
            if(edge.value instanceof byte[] bytes) assertArrayEquals(bytes,(byte[])actual);
            else assertEquals(edge.value,actual);
        }));
    }
    @Test void arrayCommasPreserveNullabilityOfSubsequentProjections() throws Exception {
        var row = vev.readGlobal(session -> Queries.arrayProjection());
        assertEquals(List.of(1, 2), row.getIds());
        assertEquals(1L, row.getTotal());
        assertNull(row.getMissing());
    }

    @Test void requiredPrimitiveBindingsAndReadsPreserveExtremaAndZero() throws Exception {
        var row = vev.readGlobal(session -> Queries.requiredPrimitives(false, Short.MIN_VALUE, 0,
                Long.MAX_VALUE, Float.NaN, Double.NEGATIVE_INFINITY));
        assertFalse(row.getB());
        assertEquals(Short.MIN_VALUE, row.getS());
        assertEquals(0, row.getI());
        assertEquals(Long.MAX_VALUE, row.getL());
        assertTrue(Float.isNaN(row.getF()));
        assertEquals(Double.NEGATIVE_INFINITY, row.getD());
    }

    @Test void unsupportedValuesAndWrongNonNullAssertionsFailInsteadOfDecodingDefaults() {
        assertThrows(IllegalArgumentException.class,()->vev.readGlobal(s->Queries.assertedNull()));
        assertThrows(java.sql.SQLException.class,()->vev.readGlobal(s->Queries.multidimensional()));
        assertThrows(java.sql.SQLException.class,()->vev.readGlobal(s->Queries.nonfiniteNumeric()));
        assertThrows(IllegalStateException.class,()->roundTrip(7,"\u0000"));
    }
}
