package org.matsim.project.simulation;

import java.io.IOException;

/**
 * Defines a task that processes the output of a successful simulation.
 *
 * @param <T> The type of the result this task produces.
 */
public interface PostProcessingTask<T extends PostProcessingResult> {

    Key<T> getKey();

    T run(RailsimSimulationResult result) throws IOException;

    /**
     * A type-safe key for storing and retrieving analysis results.
     * The key's type parameter is linked to the result type, ensuring type safety.
     */
    record Key<T extends PostProcessingResult>(String name) {
    }

}