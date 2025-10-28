package org.matsim.project.simulation;

import java.io.IOException;

/**
 * Defines a task that processes the output of a successful simulation.
 */
public interface PostProcessingTask<T extends PostProcessingResult> {

    Class<T> getResultType();

    T run(RailsimSimulationResult result) throws IOException;

}