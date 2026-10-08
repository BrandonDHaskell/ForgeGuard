package dev.cleansweep.forgeguard.workspace;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider.WorkspaceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Needs the repo produced by fixtures/build-fixtures.sh. */
@Tag("integration")
class JGitWorkspaceProviderTest {

    private static final Path FIXTURE_DIR = Path.of(
            System.getenv().getOrDefault("FORGEGUARD_FIXTURE_DIR", "/tmp/forgeguard-fixtures"),
            "intervals-java");

    private final JGitWorkspaceProvider provider = new JGitWorkspaceProvider();

    private static TaskSpec spec(String ref) {
        return new TaskSpec("t", FIXTURE_DIR.toUri().toString(), ref, null, null,
                List.of("mvn", "-B", "-o", "verify"), Limits.DEFAULTS, List.of());
    }

    private static String expectedBase() throws IOException {
        return Files.readString(Path.of("fixtures", "BASE_SHA.txt")).trim();
    }

    private static Set<String> leftoverWorkspaces() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("fg-ws-"))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void provisionsTheFixtureAtTheRecordedCommit() throws Exception {
        try (Workspace ws = provider.provision(spec("v1.0"))) {
            assertTrue(Files.isRegularFile(ws.root().resolve("pom.xml")));
            assertTrue(Files.isRegularFile(
                    ws.root().resolve("src/main/java/demo/IntervalMerger.java")));
            assertTrue(ws.baseCommit().matches("[0-9a-f]{40}"), ws.baseCommit());
            assertEquals(expectedBase(), ws.baseCommit());
        }
    }

    @Test
    void concurrentProvisionsGetDifferentDirectories() throws Exception {
        var a = CompletableFuture.supplyAsync(() -> provider.provision(spec("v1.0")));
        var b = CompletableFuture.supplyAsync(() -> provider.provision(spec("v1.0")));
        try (Workspace wa = a.get(); Workspace wb = b.get()) {
            assertNotEquals(wa.root(), wb.root());
            assertTrue(Files.isDirectory(wa.root()));
            assertTrue(Files.isDirectory(wb.root()));
        }
    }

    @Test
    void closeRemovesTheDirectory() {
        Workspace ws = provider.provision(spec("v1.0"));
        Path root = ws.root();
        assertTrue(Files.exists(root));

        ws.close();

        assertFalse(Files.exists(root));
    }

    @Test
    void badRefThrowsAndLeavesNoDirectoryBehind() throws Exception {
        Set<String> before = leftoverWorkspaces();

        var e = assertThrows(WorkspaceException.class,
                () -> provider.provision(spec("no-such-ref")));

        assertTrue(e.getMessage().contains("no-such-ref"), e.getMessage());
        assertEquals(before, leftoverWorkspaces());
    }
}
