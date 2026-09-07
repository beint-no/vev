package no.beint.vev.pg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PgCheckTreeTest {
    private static final String VARIABLE = "{VAR :varno 1 :varattno 70 :vartype 23 :vartypmod -1 :varcollid 0"
            + " :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 70 :location -1}";
    private static final String CONSTANT = "{CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4"
            + " :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 0 0 0 0 ]}";
    private static final String COMPARISON = "{OPEXPR :opno 521 :opfuncid 147 :opresulttype 16 :opretset false"
            + " :opcollid 0 :inputcollid 0 :args (" + VARIABLE + " " + CONSTANT + ") :location -1}";

    @Test
    void extractsEveryDependencyAndPreservesPhysicalAttributeNumbersBeyondRecordWidth() {
        var actual = PgCheckTree.inspect(COMPARISON);
        assertEquals(Set.of(147L), actual.functions());
        assertEquals(java.util.Map.of(521L, 147L), actual.operators());
        assertEquals(Set.of(16L, 23L), actual.types());
        assertEquals(Set.of(), actual.collations());
        assertEquals(Set.of(new PgCheckTree.Variable(70, 23, -1, 0)), actual.variables());
    }

    @Test
    void expressionInspectionRetainsTheRootTypeAlongsideAllDependencies() {
        var comparison = PgCheckTree.inspectExpression(COMPARISON);
        assertEquals(16L, comparison.resultType());
        assertEquals(PgCheckTree.inspect(COMPARISON), comparison.dependencies());
        assertEquals(23L, PgCheckTree.inspectExpression(CONSTANT).resultType());
        assertEquals(23L, PgCheckTree.inspectExpression(VARIABLE).resultType());
        String labeled = "{RELABELTYPE :arg " + CONSTANT + " :resulttype 20 :resulttypmod -1 :resultcollid 0 :relabelformat 2 :location -1}";
        assertEquals(20L, PgCheckTree.inspectExpression(labeled).resultType());
        assertEquals(Set.of(20L, 23L), PgCheckTree.inspectExpression(labeled).dependencies().types());
    }

    @Test
    void transactionClockNodesArePreciselyTypedAndAvailableOnlyToDefaults() {
        for (int operation : List.of(0, 3, 4, 5, 6, 7, 8)) {
            long type = switch (operation) { case 0 -> 1082; case 3, 4 -> 1184; case 5, 6 -> 1083; default -> 1114; };
            boolean explicitPrecision = operation == 4 || operation == 6 || operation == 8;
            for (int precision : explicitPrecision ? List.of(0, 1, 2, 3, 4, 5, 6) : List.of(-1)) {
                String tree = clock(operation, type, precision);
                var actual = PgCheckTree.inspectDefaultExpression(tree);
                assertEquals(type, actual.resultType());
                assertEquals(Set.of(type), actual.dependencies().types());
                assertEquals(Set.of(), actual.dependencies().functions());
                assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(tree));
                assertThrows(IllegalStateException.class, () -> PgCheckTree.inspectExpression(tree));
                String nested = "{NULLTEST :arg " + tree + " :nulltesttype 0 :argisrow false :location -1}";
                assertEquals(16L, PgCheckTree.inspectDefaultExpression(nested).resultType());
                assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(nested));
                assertThrows(IllegalStateException.class, () -> PgCheckTree.inspectDefaultExpression(clock(operation, 23, precision)));
            }
            for (int invalid : explicitPrecision ? List.of(-2, -1, 7, Integer.MAX_VALUE) : List.of(-2, 0, 6)) {
                assertThrows(IllegalStateException.class, () -> PgCheckTree.inspectDefaultExpression(clock(operation, type, invalid)));
            }
        }
        for (int rejected : List.of(-1, 1, 2, 9, 10, 11, 12, 13, 14, 15, Integer.MAX_VALUE)) {
            assertThrows(IllegalStateException.class, () -> PgCheckTree.inspectDefaultExpression(clock(rejected, 1184, -1)));
        }
        for (String tree : List.of(clock(3,1184,-1).replace(":type 1184", ":type 1184 :type 1184"),
                clock(3,1184,-1).replace(":typmod -1", ""), clock(3,1184,-1).replace(":location -1", ":unknown -1"))) {
            assertThrows(IllegalStateException.class, () -> PgCheckTree.inspectDefaultExpression(tree));
        }
    }

    private static String clock(int operation, long type, int precision) {
        return "{SQLVALUEFUNCTION :op " + operation + " :type " + type + " :typmod " + precision + " :location -1}";
    }

    @Test
    void rejectsUnknownNodesFieldsDuplicateFieldsAndTruncatedOrTrailingInput() {
        for (String tree : List.of("", "<>", "(" + VARIABLE + ")", VARIABLE + " extra", VARIABLE.substring(0, VARIABLE.length() - 1),
                VARIABLE.replace("VAR", "PARAM"), VARIABLE.replace(":location -1", ":newfield 0"),
                VARIABLE.replace(":location -1", ":location -1 :location -1"), VARIABLE.replace(":location -1", ""),
                CONSTANT.replace("0 0 0 0 0 0 0 0", "{}"), CONSTANT.replace("0 0 0 0 0 0 0 0", "256"),
                CONSTANT.replace("0 0 0 0 0 0 0 0", "0 0 0"))) {
            assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(tree), tree);
        }
    }

    @Test
    void rejectsNonlocalVariablesSetReturningCallsAndNoncanonicalOperatorFunctions() {
        for (String tree : List.of(VARIABLE.replace(":varno 1", ":varno 2"),
                VARIABLE.replace(":varlevelsup 0", ":varlevelsup 1"), VARIABLE.replace(":varreturningtype 0", ":varreturningtype 1"),
                VARIABLE.replace(":varattnosyn 70", ":varattnosyn 1"), VARIABLE.replace("(b)", "(b 2)"),
                COMPARISON.replace(":opretset false", ":opretset true"), COMPARISON.replace(":opfuncid 147", ":opfuncid 0"),
                COMPARISON.replace(":opno 521", ":opno 4294967296"))) {
            assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(tree), tree);
        }
    }

    @Test
    void boundsTreeLengthDepthNodesAndListWidth() {
        String nested = VARIABLE;
        for (int index = 0; index < 65; index++) nested = "{NULLTEST :arg " + nested + " :nulltesttype 0 :argisrow false :location -1}";
        String deep = nested;
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(deep));
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(" ".repeat(PgCheckTree.MAXIMUM_LENGTH + 1)));
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(
                "{BOOLEXPR :boolop and :args (" + (CONSTANT + " ").repeat(2048) + ") :location -1}"));
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(
                "{BOOLEXPR :boolop and :args (" + "<> ".repeat(2049) + ") :location -1}"));
    }

    @Test
    void checkMetadataKeepsExactTextWithoutTreatingItAsExecutableSql() {
        String text = "\nCASE\n    WHEN (label = '; -- \\" + "u000a'::text) THEN true\n    ELSE false\nEND";
        assertEquals(text, new PgCheck("probe_check", text).expression());
        for (String expression : List.of("", " ", "x".repeat(4097), "a\0b", "\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException.class, () -> new PgCheck("probe_check", expression));
        }
        for (String name : List.of("", "Unsafe", "a;drop", "x".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> new PgCheck(name, "true"));
        }
        assertEquals("'🙂'::text IS NOT NULL", new PgCheck("unicode_check", "'🙂'::text IS NOT NULL").expression());
    }
}
