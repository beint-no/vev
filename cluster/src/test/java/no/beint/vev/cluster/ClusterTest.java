package no.beint.vev.cluster;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClusterTest {
    @TempDir Path tempDir;

    @Test void startsAcceptsConnectionsAndRemovesItself() throws Exception {
        Path directory;
        int port;
        try (Cluster cluster = new Cluster("")) {
            port = cluster.port();
            // pg_ctl returns once the postmaster is accepting connections, so one build can share it.
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                assertTrue(socket.isConnected());
            }
            assertTrue(cluster.url("vev_example").endsWith("/vev_example"));
            assertEquals("vev", cluster.user());
            assertFalse(cluster.password().isBlank());
            directory = dataDirectory(cluster);
            assertTrue(Files.isDirectory(directory));
        }
        assertFalse(Files.exists(directory), "closing a cluster must remove its data directory");
    }

    @Test void cachesCleanTemplateAndClonesItForFastReuse() throws Exception {
        Path template = tempDir.resolve("schemas/vev_cached");
        Cluster initial = new Cluster("");
        String initialPassword = initial.password();
        initial.closeAndCacheTemplate(template);
        assertTrue(Files.isDirectory(template.resolve("data")));
        assertEquals(initialPassword, Files.readString(template.resolve("password")).strip());

        Path clonedDir;
        try (Cluster cloned = Cluster.fromTemplate("", template)) {
            assertEquals(initialPassword, cloned.password());
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), cloned.port())) {
                assertTrue(socket.isConnected());
            }
            clonedDir = dataDirectory(cloned);
            assertNotEquals(template, clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "closing a cloned cluster must delete its temporary working directory");
        assertTrue(Files.isDirectory(template.resolve("data")), "closing a clone must preserve the cached template");
    }

    @Test void closeIsIdempotent() throws Exception {
        Cluster cluster = new Cluster("");
        cluster.close();
        cluster.close();
    }

    private Path dataDirectory(Cluster cluster) throws Exception {
        var field = Cluster.class.getDeclaredField("directory");
        field.setAccessible(true);
        return (Path) field.get(cluster);
    }
}
