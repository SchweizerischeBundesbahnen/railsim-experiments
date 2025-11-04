package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;

/**
 * Defines a strategy for sampling departure times within one or more hourly windows.
 */
@FunctionalInterface
public interface DepartureSamplingStrategy {

    /**
     * Generates a list of departure times within an hourly window.
     *
     * @param n      The number of departures to sample per hour.
     * @param hours  The number of hourly windows to generate samples for.
     * @param random A random number generator to ensure reproducibility for stochastic strategies.
     * @return A list of departure times in seconds, relative to the start of each hour
     * (e.g., values from 0.0 to 3599.99...).
     */
    List<Double> sampleDepartures(int n, int hours, Random random);
}