package no.beint.vev.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import no.beint.vev.Session;
import no.beint.vev.compiler.Postgres;
import no.beint.vev.compiler.QueryCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;

/** Generation is not Kotlin compilation: retain both numbers, including the first cold compiler invocation. */
public final class BuildPerformance {
    private BuildPerformance() {}
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vev-build-performance-");
        Path report = Path.of(args[1]); Files.createDirectories(report.getParent());
        StringBuilder json = new StringBuilder("{\n  \"jdk\": \"" + Runtime.version() + "\",\n  \"architecture\": \"" + System.getProperty("os.arch") + "\",\n  \"samples\": [\n");
        long start = System.nanoTime();
        try (var postgres = new Postgres("")) {
            postgres.migrate(Path.of(args[0]));
            double bootstrap = seconds(start);
            try (var connection = postgres.connect()) {
                for (int count : new int[] {10, 100, 1000}) {
                    Path queries = Files.createDirectory(root.resolve("queries" + count));
                    Path output = root.resolve("generated" + count);
                    // Distinct SQL and a mixture of direct projections, CTEs, windows, lateral joins, and arrays.
                    for (int i=0; i<count; i++) {
                        String query = switch (i % 5) {
                            case 0 -> "SELECT id,name FROM departments WHERE tenant_id=:tenantId AND id>"+i;
                            case 1 -> "WITH s AS (SELECT id,tenant_id,name FROM departments) SELECT id,name FROM s WHERE tenant_id=:tenantId AND id>"+i;
                            case 2 -> "SELECT id,sum(amount) OVER(ORDER BY id) AS total FROM postings WHERE tenant_id=:tenantId AND id>"+i;
                            case 3 -> "SELECT d.id,p.total FROM departments d CROSS JOIN LATERAL (SELECT sum(amount) AS total FROM postings p WHERE p.department_id=d.id AND p.tenant_id=d.tenant_id) p WHERE d.tenant_id=:tenantId AND d.id>"+i;
                            default -> "SELECT id,amount FROM postings WHERE tenant_id=:tenantId AND id=ANY(:ids::bigint[]) AND id>"+i;
                        };
                        Files.writeString(queries.resolve("query"+i+".sql"), "-- name: query"+i+" :many\n"+query);
                    }
                    start=System.nanoTime(); QueryCompiler.compile(connection, queries, output, "benchmark.generated", "Queries");
                    double generation=seconds(start);
                    start=System.nanoTime();
                    var diagnostics=new ByteArrayOutputStream();
                    String classpath=Path.of(Session.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            +java.io.File.pathSeparator+Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    ExitCode result=new K2JVMCompiler().exec(new PrintStream(diagnostics), "-no-stdlib", "-no-reflect", "-jvm-target", "27",
                            "-classpath", classpath, "-d", root.resolve("classes"+count).toString(), output.toString());
                    if (result!=ExitCode.OK) throw new IllegalStateException(diagnostics.toString());
                    double compilation=seconds(start);
                    if(count!=10) json.append(",\n");
                    json.append(String.format(Locale.ROOT,"    {\"queries\": %d, \"bootstrapSeconds\": %.6f, \"generationSeconds\": %.6f, \"kotlinCompilationSeconds\": %.6f}",count,bootstrap,generation,compilation));
                    System.out.printf(Locale.ROOT,"%d queries: bootstrap %.3fs, generation %.3fs, Kotlin %.3fs%n",count,bootstrap,generation,compilation);
                }
            }
        } finally {
            try(var files=Files.walk(root)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        Files.writeString(report,json.append("\n  ]\n}\n").toString());
    }
    private static double seconds(long start) { return (System.nanoTime()-start)/1_000_000_000d; }
}
