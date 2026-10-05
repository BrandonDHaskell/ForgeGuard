package dev.cleansweep.forgeguard.producer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cleansweep.forgeguard.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies a pre-recorded patch and replays a pre-recorded self-report.
 *
 * <p>This is the M1 reference producer. It stands in for a coding agent and is
 * deliberately deterministic: the same task yields the same diff and the same
 * claim on every machine, so a disagreement between claim and verdict is a
 * property of the recorded data rather than of the run.
 *
 * <p>The patch is applied with {@code git apply} and no fuzz. A patch that does
 * not land cleanly on the pinned base commit is a failure to produce, not a
 * failure to verify, and surfaces as PATCH_REJECTED.
 */
public final class ScriptedProducer implements Producer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path patchFile;
    private final Path reportFile;

    public ScriptedProducer(Path patchFile, Path reportFile) {
        this.patchFile = patchFile;
        this.reportFile = reportFile;
    }

    @Override
    public ProducerResult run(Workspace workspace) {
        applyPatch(workspace.root());
        return readReport();
    }

    private void applyPatch(Path root) {
        List<String> cmd = List.of("git", "apply", "--whitespace=nowarn", patchFile.toAbsolutePath().toString());
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                throw new PatchRejectedException("git apply exited " + exit + ": " + output);
            }
        } catch (IOException e) {
            throw new PatchRejectedException("could not run git apply: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PatchRejectedException("interrupted while applying patch");
        }
    }

    private ProducerResult readReport() {
        try {
            JsonNode n = JSON.readTree(Files.readString(reportFile));
            return new ProducerResult(n.path("success").asBoolean(false), n.toString());
        } catch (IOException e) {
            // A producer that cannot state a claim is recorded as claiming nothing.
            return new ProducerResult(false, "{\"error\":\"unreadable report: " + e.getMessage() + "\"}");
        }
    }

    /** Thrown when the recorded patch will not apply to the pinned base commit. */
    public static final class PatchRejectedException extends RuntimeException {
        public PatchRejectedException(String message) {
            super(message);
        }
    }
}
