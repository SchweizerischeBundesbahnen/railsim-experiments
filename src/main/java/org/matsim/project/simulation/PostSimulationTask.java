package org.matsim.project.simulation;

import java.io.IOException;

/**
 * Defines a contract for a task that processes the output of a successful simulation.
 * Implementations must be thread-safe as they are executed in parallel.
 */
public interface PostSimulationTask {

    /**
     * @return A unique, descriptive name for this task, used as a key for storing results.
     */
    String getName();

    /**
     * Executes the analysis on the results of a simulation run.
     * The task should add its results to the provided SimulationResult object.
     *
     * @param result The result object from the completed simulation, containing run info.
     * @throws IOException if file I/O fails during analysis.
     */
    void run(RailsimSimulationResult result) throws IOException;
}