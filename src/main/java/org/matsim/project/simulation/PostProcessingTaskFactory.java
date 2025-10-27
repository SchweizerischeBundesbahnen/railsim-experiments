package org.matsim.project.simulation;

/**
 * A factory for creating new instances of a {@link PostProcessingTask}.
 * <p>
 * Ensures that each simulation job receives its own, thread-safe instance of a post-processing task,
 * preventing state interference in parallel execution.
 */
@FunctionalInterface
public interface PostProcessingTaskFactory {

    PostProcessingTask<?> create();

}