package no.beint.vev.benchmark.hibernate;

public final class HibernateBenchmarkSetup {
    private HibernateBenchmarkSetup() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("HibernateBenchmarkSetup does not accept arguments");
        }
        var summary = BenchmarkDataset.prepare(
                BenchmarkAdminConfiguration.fromEnvironment(),
                BenchmarkDatabaseConfiguration.fromEnvironment());
        System.out.println(
                "Prepared " + summary.presentCount() + " synthetic rows; checksum=" + summary.combinedChecksum());
    }
}
