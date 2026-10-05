package dev.cleansweep.forgeguard.verify;

/** The harness verdict. This, not the producer's claim, is the result of a run. */
public enum Outcome {
    PASS,
    TEST_FAIL,
    BUILD_FAIL,
    PATCH_REJECTED,
    TIMEOUT,
    RESOURCE_EXCEEDED,
    PROTECTED_PATH_MODIFIED,
    HARNESS_ERROR
}
