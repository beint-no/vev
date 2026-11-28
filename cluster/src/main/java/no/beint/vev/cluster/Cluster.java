package no.beint.vev.cluster;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A disposable, password-authenticated, loopback-only PostgreSQL 19 cluster.
 * Never connects to a user's database. Has no dependencies so that both the
 * compiler and the Gradle plugin can own a cluster without sharing a classpath.
 */
public final class Cluster implements AutoCloseable {
    private static final int MAX_START_ATTEMPTS = 3;
    private final Path directory;
    private final String bin;
    private final String password;
    private int port;
    private boolean started;
    private boolean closed;
    private final Thread shutdown;

    public Cluster(String bin) throws IOException, InterruptedException {
        this(bin, null);
    }

    /**
     * Start a disposable cluster cloned from a cleanly stopped schema template directory
     * produced by {@link #closeAndCacheTemplate(Path)}.
     */
    public static Cluster fromTemplate(String bin, Path templateDir) throws IOException, InterruptedException {
        return new Cluster(bin, java.util.Objects.requireNonNull(templateDir));
    }

    private Cluster(String bin, Path templateDir) throws IOException, InterruptedException {
        this.bin = bin == null ? "" : bin;
        directory = Files.createTempDirectory("vev-pg19-");
        shutdown = new Thread(() -> {
            try { close(); } catch (IOException failure) { System.err.println("Vev could not remove its temporary PostgreSQL cluster: " + directory); }
        }, "vev-postgres-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            String version = run(Duration.ofSeconds(10), "postgres", "--version");
            if (!version.matches("(?s).*PostgreSQL\\) 19\\..*")) throw new IOException("Vev requires PostgreSQL 19 binaries: " + version.strip());
            if (templateDir == null) {
                password = UUID.randomUUID().toString();
                Path passwordFile = directory.resolve("password");
                Files.writeString(passwordFile, password);
                try {
                    run(Duration.ofSeconds(60), "initdb", "-D", data().toString(), "--username=vev",
                            "--auth=scram-sha-256", "--pwfile=" + passwordFile, "--encoding=UTF8", "--locale=C");
                } finally { Files.deleteIfExists(passwordFile); }
            } else {
                Path templateData = templateDir.resolve("data");
                Path templatePassword = templateDir.resolve("password");
                if (!Files.isDirectory(templateData) || !Files.isRegularFile(templatePassword)) {
                    throw new IOException("Incomplete Vev schema template: " + templateDir);
                }
                password = Files.readString(templatePassword).strip();
                cloneDirectory(templateData, data());
                Files.deleteIfExists(data().resolve("postmaster.pid"));
                Files.deleteIfExists(data().resolve("postmaster.opts"));
            }
            startPostmaster();
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void startPostmaster() throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            try {
                // pg_ctl leaves the postmaster running after it returns, so one build can share one cluster.
                run(Duration.ofSeconds(60), "pg_ctl", "-D", data().toString(), "-l", directory.resolve("postgres.log").toString(), "-o",
                        "-h 127.0.0.1 -p " + port + " -c unix_socket_directories='' -c max_connections=100"
                                + " -c fsync=off -c full_page_writes=off -c synchronous_commit=off"
                                + " -c statement_timeout=120000 -c lock_timeout=30000 -c idle_in_transaction_session_timeout=600000",
                        "-w", "start");
                started = true;
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt == MAX_START_ATTEMPTS) throw failure;
            }
        }
        throw lastFailure;
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

    /**
     * Cleanly stop this cluster, atomically cache its migrated data directory as a reusable template,
     * and remove the temporary working directory.
     */
    public synchronized void closeAndCacheTemplate(Path targetDir) throws IOException {
        if (closed) return;
        try {
            if (started || Files.exists(data().resolve("postmaster.pid"))) {
                // Fast shutdown writes a clean checkpoint so cloned starts skip crash recovery.
                try { run(Duration.ofSeconds(60), "pg_ctl", "-D", data().toString(), "-m", "fast", "-w", "stop"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted stopping Vev PostgreSQL", failure); }
                started = false;
            }
            if (!Files.exists(targetDir)) {
                Path parent = targetDir.getParent();
                if (parent != null) Files.createDirectories(parent);
                setOwnerOnly(parent, true);
                Path staging = Files.createTempDirectory(parent, ".vev-staging-");
                setOwnerOnly(staging, true);
                try {
                    cloneDirectory(data(), staging.resolve("data"));
                    Path passwordFile = staging.resolve("password");
                    Files.writeString(passwordFile, password);
                    setOwnerOnly(passwordFile, false);
                    try {
                        Files.move(staging, targetDir, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException ignored) {
                        deleteTree(staging);
                    } catch (AtomicMoveNotSupportedException fallback) {
                        try { Files.move(staging, targetDir); }
                        catch (FileAlreadyExistsException ignored) { deleteTree(staging); }
                    }
                } catch (IOException | RuntimeException failure) {
                    try { deleteTree(staging); } catch (IOException ignored) { }
                    throw failure;
                }
            }
        } finally {
            close();
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (started || Files.exists(data().resolve("postmaster.pid"))) {
            try { run(Duration.ofSeconds(60), "pg_ctl", "-D", data().toString(), "-m", "immediate", "-w", "stop"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted stopping Vev PostgreSQL", failure); }
            started = false;
        }
        deleteTree(directory);
        closed = true;
        if (Thread.currentThread() != shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException shuttingDown) { }
        }
    }

    private static void cloneDirectory(Path source, Path target) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("mac")
                ? List.of("cp", "-cR", source.toString(), target.toString())
                : os.contains("linux")
                ? List.of("cp", "-a", "--reflink=auto", source.toString(), target.toString())
                : null;
        if (command != null) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    setOwnerOnly(target, true);
                    return;
                }
                process.destroyForcibly();
                deleteTree(target);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted cloning Vev schema template", interrupted);
            } catch (IOException ignored) {
                deleteTree(target);
            }
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        setOwnerOnly(target, true);
    }

    private static void setOwnerOnly(Path path, boolean directory) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) { }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
