package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates departure with a fixed headway (equal time intervals)
 * <p>
 * The first departure in the first hour is randomly offset within the headway interval so that all departures fit within the hour.
 * All subsequent departures are spaced evenly according to the headway, and the same pattern is repeated for each hour.
 * <p>
 * Example for 4 departures per hour (headway = 15 min): the first train may start at a random time between 0 and 15 minutes,
 * and the others follow every 15 minutes. This pattern is repeated for each hour.
 */
public class HeadwayDepartureSampling implements DepartureSamplingStrategy {
    private static final double SECONDS_PER_HOUR = 3600.0;

    @Override
    public List<Double> sampleDepartures(int n, int hours, Random random) {
        if (n <= 0 || hours <= 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid sampling parameters: n=%d (departures per hour), hours=%d — both must be positive.", n,
                    hours));
        }

        // Single departure: start at 0
        List<Double> departureOffsets;
        if (n == 1) {
            departureOffsets = List.of(0.0);
        } else {
            double headway = SECONDS_PER_HOUR / n;
            double firstDeparture = random.nextDouble() * headway;  // random start within the first headway interval
            departureOffsets = IntStream.range(0, n)
                    .mapToDouble(i -> firstDeparture + i * headway)
                    .boxed()
                    .collect(Collectors.toList());
        }

        // Repeat the same offsets across all hours
        return IntStream.range(0, hours)
                .boxed()
                .flatMap(h -> departureOffsets.stream().map(d -> d + h * SECONDS_PER_HOUR))
                .collect(Collectors.toList());
    }
}
