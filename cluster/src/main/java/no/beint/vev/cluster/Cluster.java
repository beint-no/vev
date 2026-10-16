package no.beint.vev.cluster;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A disposable, password-authenticated, loopback-only PostgreSQL 18 cluster.
 * Never connects to a user's database. Has no dependencies so that both the
 * compiler and the Gradle plugin can own a cluster without sharing a classpath.
 */
public final class Cluster implements AutoCloseable {
    private final Path directory;
    private final String bin;
    private final String password = UUID.randomUUID().toString();
    private final int port;
    private boolean started;
    private boolean closed;
    private final Thread shutdown;

    public Cluster(String bin) throws IOException, InterruptedException {
        this.bin = bin == null ? "" : bin;
        directory = Files.createTempDirectory("vev-pg18-");
        shutdown = new Thread(() -> {
            try { close(); } catch (IOException failure) { System.err.println("Vev could not remove its temporary PostgreSQL cluster: " + directory); }
        }, "vev-postgres-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        try {
            String version = run(Duration.ofSeconds(10), "postgres", "--version");
            if (!version.matches("(?s).*PostgreSQL\\) 18\\..*")) throw new IOException("Vev requires PostgreSQL 18 binaries: " + version.strip());
            Path passwordFile = directory.resolve("password");
            Files.writeString(passwordFile, password);
            try {
                run(Duration.ofSeconds(60), "initdb", "-D", data().toString(), "--username=vev",
                        "--auth=scram-sha-256", "--pwfile=" + passwordFile, "--encoding=UTF8", "--locale=C");
            } finally { Files.deleteIfExists(passwordFile); }
            // pg_ctl leaves the postmaster running after it returns, so one build can share one cluster.
            run(Duration.ofSeconds(60), "pg_ctl", "-D", data().toString(), "-l", directory.resolve("postgres.log").toString(), "-o",
                    "-h 127.0.0.1 -p " + port + " -c unix_socket_directories='' -c max_connections=100"
                            + " -c fsync=off -c full_page_writes=off -c synchronous_commit=off"
                            + " -c statement_timeout=120000 -c lock_timeout=30000 -c idle_in_transaction_session_timeout=600000",
                    "-w", "start");
            started = true;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public int port() { return port; }
    public String user() { return "vev"; }
    public String password() { return password; }
    public String url(String database) { return "jdbc:postgresql://127.0.0.1:" + port + "/" + database; }
    public String url() { return url("postgres"); }

    private Path data() { return directory.resolve("data"); }

    private String run(Duration timeout, String program, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(bin.isBlank() ? program : Path.of(bin, program).toString());
        command.addAll(List.of(arguments));
        Path output = directory.resolve("command.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException(program + " timed out");
            }
            String result = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(program + " failed: " + result);
            return result;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            throw interrupted;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (started || Files.exists(data().resolve("postmaster.pid"))) {
            try { run(Duration.ofSeconds(60), "pg_ctl", "-D", data().toString(), "-m", "immediate", "-w", "stop"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted stopping Vev PostgreSQL", failure); }
            started = false;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        closed = true;
        if (Thread.currentThread() != shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException shuttingDown) { }
        }
    }
}
