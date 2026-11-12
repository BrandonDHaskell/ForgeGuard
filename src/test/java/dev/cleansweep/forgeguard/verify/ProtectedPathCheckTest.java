package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The globs that ship in the committed task files, not hand-written copies of them. */
class ProtectedPathCheckTest {

    private static ProtectedPathCheck fromTask(String name) {
        var spec = new TaskSpecLoader().load(Path.of("tasks", name + ".yaml")).spec();
        return new ProtectedPathCheck(spec.protectedPaths());
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/test/java/demo/IntervalMergerTest.java", "pom.xml"})
    void mavenTasksProtectTestsAndBuildFile(String path) {
        assertEquals(List.of(path), fromTask("correct").firstViolation(List.of(path)).stream().toList());
    }

    /** Each is a way to change pytest's results without editing a test file. */
    @ParameterizedTest
    @ValueSource(strings = {"tests/test_merger.py", "tests/helpers/new_file.py", "conftest.py",
            "intervals/conftest.py", "pyproject.toml", "pytest.ini", "setup.cfg", "tox.ini"})
    void pytestTasksProtectTestsAndEverythingThatSteersPytest(String path) {
        assertEquals(List.of(path), fromTask("py-correct").firstViolation(List.of(path)).stream().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"intervals/merger.py", "intervals/interval.py", "README.md"})
    void pytestTasksLeaveSourceEditable(String path) {
        assertTrue(fromTask("py-correct").firstViolation(List.of(path)).isEmpty(), path);
    }
}
