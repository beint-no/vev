package no.beint.vev.cluster;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClusterTest {
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
