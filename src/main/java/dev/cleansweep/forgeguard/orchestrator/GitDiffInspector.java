package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads what the producer actually changed by shelling out to git in the
 * workspace. The producer's own files_touched list is never consulted; it is
 * recorded as part of the claim and treated as unverified.
 */
public final class GitDiffInspector implements DiffInspector {

    /**
     * Pins every diff option that user or repository git config could otherwise
     * change, so the same logical change yields the same bytes, and the same
     * hash, on any machine.
     */
    private static final String[] DIFF = {
            "diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-renames",
            "--diff-algorithm=myers", "--no-indent-heuristic", "--unified=3",
            "--src-prefix=a/", "--dst-prefix=b/"};

    /**
     * Stages everything, then diffs the index against the base commit.
     *
     * <p>A plain {@code git diff} compares the working tree to the index, so it
     * misses files the producer created and anything it committed. Staging with
     * {@code add -A} brings in new and deleted files; diffing against the base
     * commit rather than HEAD keeps committed changes visible. Both matter for
     * the protected-path check: a change it cannot see, it cannot reject.
     *
     * <p>The global excludes file is switched off so a host's personal ignore
     * rules cannot hide a file on one laptop and not the other. The repository's
     * own .gitignore still applies, and an edit to it shows up in the diff.
     */
    @Override
    public Diff inspect(Workspace workspace) {
        String base = workspace.baseCommit();
        git(workspace, "-c", "core.excludesFile=/dev/null", "add", "-A");
        String unified = git(workspace, withArgs(DIFF, "--cached", base));
        String head = git(workspace, "rev-parse", "HEAD").trim();
        List<String> touched = Arrays.stream(
                        git(workspace, withArgs(DIFF, "--cached", "--name-only", base)).split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new Diff(head, sha256(normalize(unified)), touched, unified);
    }

    /**
     * Strips the index line, whose blob hashes vary with file mode and git
     * version, so the same logical change hashes identically on both laptops.
     */
    private static String normalize(String diff) {
        return Arrays.stream(diff.split("\n"))
                .filter(l -> !l.startsWith("index "))
                .map(l -> l.stripTrailing())
                .reduce("", (a, b) -> a + b + "\n");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String[] withArgs(String[] base, String... extra) {
        String[] all = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    private static String git(Workspace ws, String... args) {
        // quotepath=off keeps non-ASCII paths literal instead of octal-escaped.
        String[] cmd = withArgs(new String[] {"git", "-c", "core.quotepath=off"}, args);
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(ws.root().toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }
}
