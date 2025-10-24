package org.matsim.project.sampling.strategy;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A sampling strategy that generates departure times randomly within a one-hour window.
 * The generated departure times are sorted chronologically.
 */
public class RandomDepartureSampling implements DepartureSamplingStrategy {
    private static final int SECONDS_PER_HOUR = 3600;

    @Override
    public List<Double> sampleDepartures(int amount, Random random) {
        if (amount <= 0) {
            return Collections.emptyList();
        }
        return Stream.generate(() -> random.nextDouble() * SECONDS_PER_HOUR)
                .limit(amount)
                .sorted()
                .collect(Collectors.toList());
    }
}