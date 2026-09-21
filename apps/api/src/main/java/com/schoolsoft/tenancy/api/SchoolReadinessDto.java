package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.util.List;

/**
 * Whether a school can open, and what is standing in the way.
 *
 * <p>{@code canGoLive} is true when every blocking step is done. It says
 * nothing about {@code lifecycle}: a school already live reads as ready, and
 * a school that has been suspended reads its steps the same way it always did.</p>
 */
public record SchoolReadinessDto(
    java.util.UUID schoolId,
    String lifecycle,
    Instant wentLiveAt,
    boolean canGoLive,
    List<OnboardingStepDto> steps
) {}
