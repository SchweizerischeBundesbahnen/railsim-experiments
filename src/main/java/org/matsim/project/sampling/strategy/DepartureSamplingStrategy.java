package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;

/**
 * Defines a strategy for sampling departure times.
 */
@FunctionalInterface
public interface DepartureSamplingStrategy {

    /**
     * Generates a list of departure times.
     *
     * @param n      The number of departures to sample per period.
     * @param period The time period in seconds over which 'n' departures are distributed.
     *               This pattern is repeated for the entire simulation duration.
     * @param time   The total duration of the simulation in seconds.
     * @param random A random number generator for reproducibility.
     * @return A list of absolute departure times in seconds from the start of the simulation.
     */
    List<Double> sampleDepartures(int n, int period, int time, Random random);
}