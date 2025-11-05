package org.matsim.project.sampling.strategy;

import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A sampling strategy that generates random departure times within one or more hourly windows.
 * <p>
 * Each hour contains {@code n} departures occurring at the same relative times,
 * randomly determined once for the first hour and repeated for subsequent hours.
 * The generated departure times are sorted chronologically.
 */
@Log4j2
public class RandomDepartureSampling implements DepartureSamplingStrategy {
    private static final int SECONDS_PER_HOUR = 3600;

    @Override
    public List<Double> sampleDepartures(int n, int hours, Random random) {
        if (n <= 0 || hours <= 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid sampling parameters: n=%d (departures per hour), hours=%d — both must be positive.", n,
                    hours));
        }

        // sample n random departure times within the first hour
        List<Double> departureOffsets = Stream.generate(() -> random.nextDouble() * SECONDS_PER_HOUR)
                .limit(n)
                .sorted()
                .toList();

        // repeat the same pattern across all hours
        return IntStream.range(0, hours)
                .boxed()
                .flatMap(h -> departureOffsets.stream().map(d -> d + h * SECONDS_PER_HOUR))
                .toList();
    }
}
