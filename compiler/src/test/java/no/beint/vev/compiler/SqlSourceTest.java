package no.beint.vev.compiler;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlSourceTest {
    private SqlSource parse(String sql) { return SqlSource.parse(Path.of("test.sql"), "-- name: find :many\n" + sql); }

    @Test void preservesQuotedTokensAndCasts() {
        var source = parse("SELECT ':hidden', $$:hidden;$$, $tag$:hidden$tag$, \"column:quoted\", :id::int, :id -- :hidden\n /* :hidden /* nested */ */");
        assertEquals(List.of("id", "id"), source.parameters());
        assertTrue(source.sql().contains("?::int"));
    }
    @Test void escapedStringsAndQuestionOperators() {
        var source = parse("SELECT E'foo\\\':hidden', :json::jsonb ? :key, :json::jsonb ?| array['x']");
        assertEquals(List.of("json", "key", "json"), source.parameters());
        assertTrue(source.sql().contains("??"));
        assertTrue(source.sql().contains("??|"));
    }
    @ParameterizedTest @ValueSource(strings = {
        "SELECT 1; DELETE FROM customer", "SELECT 'unterminated", "SELECT $$unterminated",
        "SELECT 1 /* unterminated", "CREATE TABLE x(id int)", "SELECT $1", "SELECT :session"
    }) void rejectsInvalidSources(String sql) { assertThrows(IllegalArgumentException.class, () -> parse(sql)); }
    @ParameterizedTest @ValueSource(strings = {
        "SELECT t.id FROM t LEFT JOIN u ON true", "SELECT t.id FROM t RIGHT JOIN u ON true",
        "SELECT t.id FROM t FULL JOIN u ON true", "SELECT id FROM t UNION SELECT NULL",
        "SELECT id FROM t GROUP BY ROLLUP(id)", "WITH x AS (SELECT id FROM t) SELECT id FROM x",
        "SELECT (SELECT id FROM t LIMIT 1)", "UPDATE t SET id = 1 RETURNING id"
    }) void complexResultsRemainNullable(String sql) { assertFalse(parse(sql).directNullability()); }
    @Test void innerJoinsPreserveOriginNullability() { assertTrue(parse("SELECT t.id FROM t JOIN u ON u.id=t.id").directNullability()); }
    @ParameterizedTest @ValueSource(strings = {
        "SELECT * FROM t", "SELECT ALL * FROM t", "SELECT DISTINCT ON (id) * FROM t",
        "SELECT t.* FROM t", "SELECT (t).* FROM t", "DELETE FROM t RETURNING *"
    }) void rejectsUnstableWildcardResults(String sql) { assertThrows(IllegalArgumentException.class, () -> parse(sql)); }
    @Test void directiveErrorsAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> parse("-- nullable: absent\nSELECT :id"));
        assertThrows(IllegalArgumentException.class, () -> parse("-- rows: 0\nSELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> parse("SELECT 1\n-- rows: 4"));
        assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(Path.of("x.sql"), "SELECT 1"));
    }
}
