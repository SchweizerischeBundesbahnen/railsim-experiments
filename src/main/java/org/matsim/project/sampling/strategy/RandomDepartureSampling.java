package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A sampling strategy that generates a repeating pattern of random departure times.
 * <p>
 * A set of 'n' random departure offsets is generated once for the initial time period.
 * This exact same pattern of offsets is then repeated for all subsequent periods
 * throughout the simulation's duration. This creates a consistent, cyclical demand pattern.
 * All final departure times are rounded to the nearest whole second.
 */
public class RandomDepartureSampling implements DepartureSamplingStrategy {

    @Override
    public List<Double> sampleDepartures(int n, int period, int time, Random random) {
        if (n <= 0 || time <= 0 || period <= 0) {
            throw new IllegalArgumentException(
                    String.format("Invalid sampling parameters: n=%d, period=%d, time=%d — all must be positive.", n,
                            period, time));
        }

        // determine number of periods to cover the entire simulation time
        int periodsToGenerate = (time + period - 1) / period;

        // sample n random departure times ONCE
        List<Double> departureOffsets = Stream.generate(() -> random.nextDouble() * period).limit(n).sorted().toList();

        // repeat the pattern for all needed periods, cutoff at simulation time
        return IntStream.range(0, periodsToGenerate)
                .boxed()
                .flatMap(p -> departureOffsets.stream().map(d -> d + (double) p * period))
                .filter(departureTime -> departureTime < time)
                .map(d -> (double) Math.round(d))
                .toList();
    }
}