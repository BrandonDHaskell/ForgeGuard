package dev.cleansweep.forgeguard.store;

/**
 * A worker wrote to a run it no longer holds: another claim took the run over
 * after this worker's lease expired. Nothing was written.
 *
 * <p>This is an expected outcome for a worker that stalled past its lease, not
 * a bug and not bad input, so it is not an IllegalArgumentException. The worker
 * should abandon the run; the new holder's attempt is the one that counts.
 */
public final class LeaseLostException extends RuntimeException {

    private final transient Claim claim;

    public LeaseLostException(Claim claim) {
        super("lease lost: run " + claim.runId() + " attempt " + claim.attempt()
                + " is no longer held by " + claim.workerId());
        this.claim = claim;
    }

    /** The claim whose lease was lost. */
    public Claim claim() {
        return claim;
    }
}
