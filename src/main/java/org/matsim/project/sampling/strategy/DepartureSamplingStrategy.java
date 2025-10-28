package org.matsim.project.sampling.strategy;

import java.util.List;
import java.util.Random;

/**
 * Defines a strategy for sampling a number of departure times within a one-hour window.
 */
@FunctionalInterface
public interface DepartureSamplingStrategy {
    /**
     * Generates a list of departure times.
     *
     * @param amount The number of departures to sample within the hour.
     * @param random A random number generator for stochastic strategies to ensure reproducibility.
     * @return A list of departure times in seconds, relative to the start of the hour (e.g., values from 0.0 to 3599.99...).
     */
    List<Double> sampleDepartures(int amount, Random random);
}