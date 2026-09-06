package no.beint.vev.pg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads PostgreSQL 18's stored expression nodes as bounded data; never parses or evaluates SQL. */
final class PgCheckTree {
    static final int MAXIMUM_LENGTH = 1024 * 1024;
    private static final int MAXIMUM_NODES = 2048;
    private static final int MAXIMUM_DEPTH = 64;
    private static final Set<String> FUNCTION_FIELDS = Set.of("funcid", "opfuncid", "hashfuncid", "negfuncid");
    private static final Set<String> TYPE_FIELDS = Set.of("vartype", "consttype", "resulttype", "opresulttype",
            "funcresulttype", "array_typeid", "element_typeid", "casetype", "typeId", "coalescetype");
    private static final Set<String> COLLATION_FIELDS = Set.of("varcollid", "constcollid", "resultcollid",
            "opcollid", "inputcollid", "funccollid", "array_collid", "casecollid", "collation", "coalescecollid", "collOid");
    private static final String OP_FIELDS = "opno opfuncid opresulttype opretset opcollid inputcollid args location";
    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            fields("VAR", "varno varattno vartype vartypmod varcollid varnullingrels varlevelsup varreturningtype varnosyn varattnosyn location"),
            fields("CONST", "consttype consttypmod constcollid constlen constbyval constisnull location constvalue"),
            fields("RELABELTYPE", "arg resulttype resulttypmod resultcollid relabelformat location"),
            fields("COLLATEEXPR", "arg collOid location"),
            fields("OPEXPR", OP_FIELDS), fields("NULLIFEXPR", OP_FIELDS), fields("DISTINCTEXPR", OP_FIELDS),
            fields("FUNCEXPR", "funcid funcresulttype funcretset funcvariadic funcformat funccollid inputcollid args location"),
            fields("NULLTEST", "arg nulltesttype argisrow location"),
            fields("BOOLEANTEST", "arg booltesttype location"),
            fields("BOOLEXPR", "boolop args location"),
            fields("ARRAYEXPR", "array_typeid array_collid element_typeid elements multidims list_start list_end location"),
            fields("SCALARARRAYOPEXPR", "opno opfuncid hashfuncid negfuncid useOr inputcollid args location"),
            fields("ARRAYCOERCEEXPR", "arg elemexpr resulttype resulttypmod resultcollid coerceformat location"),
            fields("CASEEXPR", "casetype casecollid arg args defresult location"),
            fields("CASEWHEN", "expr result location"),
            fields("CASETESTEXPR", "typeId typeMod collation"),
            fields("COALESCEEXPR", "coalescetype coalescecollid args location"));

    record Variable(int number, long type, int typeModifier, long collation) {
    }

    record Dependencies(Set<Long> functions, Map<Long, Long> operators, Set<Long> types,
                        Set<Long> collations, Set<Variable> variables, Map<Long, Set<Long>> functionInputs) {
    }

    private record Node(String tag, Map<String, Object> fields) {
    }

    private final String source;
    private int position;
    private int nodes;
    private final Set<Long> functions = new HashSet<>();
    private final Map<Long, Long> operators = new HashMap<>();
    private final Set<Long> types = new HashSet<>();
    private final Set<Long> collations = new HashSet<>();
    private final Set<Variable> variables = new HashSet<>();
    private final Map<Long, Set<Long>> functionInputs = new HashMap<>();

    private PgCheckTree(String source) {
        if (source == null || source.isEmpty() || source.length() > MAXIMUM_LENGTH) throw unsupported();
        this.source = source;
    }

    static Dependencies inspect(String source) {
        PgCheckTree parser = new PgCheckTree(source);
        if (!(parser.value(0) instanceof Node)) throw unsupported();
        parser.whitespace();
        if (parser.position != source.length()) throw unsupported();
        Map<Long, Set<Long>> inputs = new HashMap<>();
        parser.functionInputs.forEach((function, types) -> inputs.put(function, Set.copyOf(types)));
        return new Dependencies(Set.copyOf(parser.functions), Map.copyOf(parser.operators), Set.copyOf(parser.types),
                Set.copyOf(parser.collations), Set.copyOf(parser.variables), Map.copyOf(inputs));
    }

    private Object value(int depth) {
        if (depth > MAXIMUM_DEPTH) throw unsupported();
        whitespace();
        if (position == source.length()) throw unsupported();
        return switch (source.charAt(position)) {
            case '{' -> node(depth + 1);
            case '(' -> list(depth + 1);
            default -> atom();
        };
    }

    private Node node(int depth) {
        if (++nodes > MAXIMUM_NODES) throw unsupported();
        expect('{');
        String tag = atom();
        Set<String> expected = FIELDS.get(tag);
        if (expected == null) throw unsupported();
        Map<String, Object> fields = new HashMap<>();
        while (!at('}')) {
            String field = atom();
            if (!field.startsWith(":")) throw unsupported();
            field = field.substring(1);
            if (!expected.contains(field) || fields.containsKey(field)) throw unsupported();
            fields.put(field, field.equals("constvalue") ? datum() : value(depth));
        }
        expect('}');
        if (!fields.keySet().equals(expected)) throw unsupported();
        Node node = new Node(tag, fields);
        inspect(node);
        return node;
    }

    private List<Object> list(int depth) {
        expect('(');
        List<Object> result = new ArrayList<>();
        while (!at(')')) {
            if (result.size() == MAXIMUM_NODES) throw unsupported();
            result.add(value(depth));
        }
        expect(')');
        return result;
    }

    private String datum() {
        String length = atom();
        if (length.equals("<>")) return length;
        long size = number(length);
        if (size < 0 || size > PgCheck.MAXIMUM_EXPRESSION_LENGTH * 4L) throw unsupported();
        expect('[');
        int bytes = 0;
        while (!at(']')) {
            long value = number(atom());
            if (value < -128 || value > 255 || ++bytes > PgCheck.MAXIMUM_EXPRESSION_LENGTH * 4) throw unsupported();
        }
        expect(']');
        // Pass-by-value datums include a machine word even when their declared width is smaller.
        if (bytes != size && bytes != Long.BYTES) throw unsupported();
        return length;
    }

    private void inspect(Node node) {
        for (var field : node.fields().entrySet()) {
            String name = field.getKey();
            if (FUNCTION_FIELDS.contains(name)) {
                long function = oid(field.getValue());
                if (function == 0 && (name.equals("funcid") || name.equals("opfuncid"))) throw unsupported();
                add(functions, function);
            }
            if (TYPE_FIELDS.contains(name)) {
                long type = oid(field.getValue());
                if (type == 0) throw unsupported();
                types.add(type);
            }
            if (COLLATION_FIELDS.contains(name)) add(collations, oid(field.getValue()));
        }
        Map<String, Object> fields = node.fields();
        if (node.tag().equals("FUNCEXPR")) {
            Set<Long> inputTypes = functionInputs.computeIfAbsent(oid(fields.get("funcid")), ignored -> new HashSet<>());
            Object arguments = fields.get("args");
            if (arguments instanceof List<?> list) {
                for (Object argument : list) {
                    if (!(argument instanceof Node input)) throw unsupported();
                    long type = resultType(input);
                    inputTypes.add(type);
                    types.add(type);
                }
            } else if (!arguments.equals("<>")) throw unsupported();
        }
        if (fields.containsKey("opno")) {
            long operator = oid(fields.get("opno"));
            long function = oid(fields.get("opfuncid"));
            if (operator == 0 || function == 0) throw unsupported();
            Long previous = operators.putIfAbsent(operator, function);
            if (previous != null && previous != function) throw unsupported();
        }
        if (fields.containsKey("opretset") && !fields.get("opretset").equals("false")
                || fields.containsKey("funcretset") && !fields.get("funcretset").equals("false")) throw unsupported();
        if (node.tag().equals("SCALARARRAYOPEXPR")
                && (oid(fields.get("hashfuncid")) != 0 || oid(fields.get("negfuncid")) != 0)) throw unsupported();
        if (node.tag().equals("NULLTEST") && !fields.get("argisrow").equals("false")) throw unsupported();
        if (node.tag().equals("ARRAYEXPR") && !fields.get("multidims").equals("false")) throw unsupported();
        if (node.tag().equals("VAR")) {
            int attribute = integer(fields.get("varattno"));
            if (attribute <= 0 || integer(fields.get("varno")) != 1 || integer(fields.get("varnosyn")) != 1
                    || integer(fields.get("varattnosyn")) != attribute || integer(fields.get("varlevelsup")) != 0
                    || integer(fields.get("varreturningtype")) != 0 || !fields.get("varnullingrels").equals(List.of("b"))) {
                throw unsupported();
            }
            variables.add(new Variable(attribute, oid(fields.get("vartype")), integer(fields.get("vartypmod")),
                    oid(fields.get("varcollid"))));
        }
    }

    private static void add(Set<Long> target, long value) {
        if (value != 0) target.add(value);
    }

    private static long resultType(Node node) {
        return switch (node.tag()) {
            case "VAR" -> oid(node.fields().get("vartype"));
            case "CONST" -> oid(node.fields().get("consttype"));
            case "RELABELTYPE", "ARRAYCOERCEEXPR" -> oid(node.fields().get("resulttype"));
            case "OPEXPR", "NULLIFEXPR" -> oid(node.fields().get("opresulttype"));
            case "FUNCEXPR" -> oid(node.fields().get("funcresulttype"));
            case "ARRAYEXPR" -> oid(node.fields().get("array_typeid"));
            case "CASEEXPR" -> oid(node.fields().get("casetype"));
            case "CASETESTEXPR" -> oid(node.fields().get("typeId"));
            case "COALESCEEXPR" -> oid(node.fields().get("coalescetype"));
            case "NULLTEST", "BOOLEANTEST", "BOOLEXPR", "DISTINCTEXPR", "SCALARARRAYOPEXPR" -> 16;
            case "COLLATEEXPR" -> {
                if (!(node.fields().get("arg") instanceof Node argument)) throw unsupported();
                yield resultType(argument);
            }
            default -> throw unsupported();
        };
    }

    private static long oid(Object value) {
        if (!(value instanceof String atom)) throw unsupported();
        long result = number(atom);
        if (result < 0 || result > 0xffff_ffffL) throw unsupported();
        return result;
    }

    private static int integer(Object value) {
        if (!(value instanceof String atom)) throw unsupported();
        long result = number(atom);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw unsupported();
        return (int) result;
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw unsupported();
        }
    }

    private String atom() {
        whitespace();
        int start = position;
        while (position < source.length() && !Character.isWhitespace(source.charAt(position))
                && "{}()[]".indexOf(source.charAt(position)) < 0) position++;
        if (position == start || position - start > 64) throw unsupported();
        return source.substring(start, position);
    }

    private boolean at(char character) {
        whitespace();
        return position < source.length() && source.charAt(position) == character;
    }

    private void expect(char character) {
        if (!at(character)) throw unsupported();
        position++;
    }

    private void whitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
    }

    private static Map.Entry<String, Set<String>> fields(String tag, String fields) {
        return Map.entry(tag, Set.of(fields.split(" ")));
    }

    private static IllegalStateException unsupported() {
        return new IllegalStateException("Check constraint uses an unsupported PostgreSQL 18 expression tree");
    }
}
