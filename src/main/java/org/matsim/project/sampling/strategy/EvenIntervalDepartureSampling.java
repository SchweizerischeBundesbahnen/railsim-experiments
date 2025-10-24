package org.matsim.project.sampling.strategy;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A sampling strategy that generates departure times at even intervals within a one-hour window.
 * For instance, 4 trains per hour will depart at 0, 15, 30, and 45 minutes past the hour.
 */
public class EvenIntervalDepartureSampling implements DepartureSamplingStrategy {
    private static final double SECONDS_PER_HOUR = 3600.0;

    @Override
    public List<Double> sampleDepartures(int amount, Random random) {
        if (amount <= 0) {
            return Collections.emptyList();
        }
        if (amount == 1) {
            // A single train departs at the start of the hour.
            return Collections.singletonList(0.0);
        }
        // Calculate the precise time interval between each departure.
        final double interval = SECONDS_PER_HOUR / amount;
        return IntStream.range(0, amount).mapToDouble(i -> i * interval).boxed().collect(Collectors.toList());
    }
}