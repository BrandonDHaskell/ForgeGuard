package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class InMemoryRunStoreTest extends RunStoreContractTest {

    private final InMemoryRunStore store = new InMemoryRunStore();

    @Override
    protected RunStore store() {
        return store;
    }

    @Test
    void logsReadBackInAppendOrder() {
        UUID id = store.create(spec());
        store.appendLog(id, "stdout", "first\n");
        store.appendLog(id, "stdout", "second\n");
        store.appendLog(id, "stderr", "");

        assertEquals("first\nsecond\n", store.logs(id, "stdout"));
        assertEquals("", store.logs(id, "stderr"));
    }
}
