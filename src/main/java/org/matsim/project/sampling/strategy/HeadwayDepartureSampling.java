package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A sampling strategy that generates a continuous stream of departures at a fixed interval (headway).
 * <p>
 * The time between any two consecutive departures is constant. This headway is calculated
 * by dividing the {@code period} by the number of trains {@code n}.
 * <p>
 * To avoid all schedules starting at time 0, the very first departure is given a random initial offset,
 * chosen from the range [0, headway). All subsequent departures follow from that point at the fixed headway.
 * All final departure times are rounded to the nearest whole second.
 */
public class HeadwayDepartureSampling implements DepartureSamplingStrategy {

    @Override
    public List<Double> sampleDepartures(int n, int period, int time, Random random) {
        if (n <= 0 || time <= 0 || period <= 0) {
            throw new IllegalArgumentException(
                    String.format("Invalid sampling parameters: n=%d, period=%d, time=%d — all must be positive.", n,
                            period, time));
        }

        // calculate the fixed time interval (headway) between consecutive departures
        double headway = (double) period / n;

        // sample a single random starting offset for the very first train
        double initialOffset = random.nextDouble() * headway;

        // determine a safe upper bound for the number of departures to generate
        int maxDeparturesToGenerate = (int) Math.ceil(time / headway) + 1;

        // generate a continuous list of departures
        return IntStream.range(0, maxDeparturesToGenerate)
                .mapToDouble(i -> initialOffset + i * headway)
                .filter(departureTime -> departureTime < time)
                .map(Math::round)
                .boxed()
                .collect(Collectors.toList());
    }
}