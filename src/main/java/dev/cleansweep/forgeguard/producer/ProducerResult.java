package dev.cleansweep.forgeguard.producer;

/**
 * What the producer claims it did. Recorded as evidence of the claim,
 * never used to decide the verdict.
 */
public record ProducerResult(boolean reportedSuccess, String report) {}
