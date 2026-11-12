package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerificationStrategiesTest {

    private record Named(String name) implements VerificationStrategy {
        @Override
        public Verification verify(Workspace workspace, Sandbox sandbox, TaskSpec spec) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void builtInKnowsMaven() {
        assertInstanceOf(MavenStrategy.class, VerificationStrategies.builtIn().get("maven"));
    }

    @Test
    void looksUpByName() {
        var b = new Named("b");
        var strategies = new VerificationStrategies(List.of(new Named("a"), b));

        assertSame(b, strategies.get("b"));
    }

    @Test
    void unknownNameListsTheKnownOnes() {
        var strategies = new VerificationStrategies(List.of(new Named("b"), new Named("a")));

        String msg = assertThrows(IllegalArgumentException.class, () -> strategies.get("gradle"))
                .getMessage();
        assertEquals("unknown strategy: gradle (expected one of [a, b])", msg);
    }

    @Test
    void duplicateNamesAreRejected() {
        var dupes = List.<VerificationStrategy>of(new Named("maven"), new Named("maven"));

        assertThrows(IllegalArgumentException.class, () -> new VerificationStrategies(dupes));
    }
}
