package com.schoolsoft.tenancy.api;

/**
 * One setup step, as the checklist shows it. {@code done} is derived from the
 * rows the step is about — {@code count} is how many were found, so a screen
 * can say "12 grades" rather than a tick.
 *
 * <p>A skipped step is one the school said does not apply to it, with the
 * reason it gave. Only a non-blocking step can be skipped, so a skip never
 * explains away an empty school.</p>
 */
public record OnboardingStepDto(
    String key,
    String label,
    String why,
    boolean blocking,
    boolean done,
    long count,
    /** What `count` counts, already agreeing with it: "1 campus", "7 subjects". */
    String unit,
    boolean skipped,
    String skipReason
) {}
