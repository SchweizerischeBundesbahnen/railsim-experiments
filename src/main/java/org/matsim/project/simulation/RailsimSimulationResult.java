package org.matsim.project.simulation;

import lombok.Getter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A container for the complete outcome of a simulation pipeline run, including its
 * status, the original job, and a type-safe map for storing results from post-processing tasks.
 */
@Getter
public class RailsimSimulationResult {

    private final RailsimSimulationJob job;
    private final Status status;
    private final String errorMessage;
    private final Map<Class<?>, PostProcessingResult> postProcessingResults = new ConcurrentHashMap<>();

    private RailsimSimulationResult(RailsimSimulationJob job, Status status, String errorMessage) {
        this.job = job;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static RailsimSimulationResult success(RailsimSimulationJob job) {
        return new RailsimSimulationResult(job, Status.SUCCESS, null);
    }

    public static RailsimSimulationResult failure(RailsimSimulationJob job, Throwable error) {
        String message =
                (error != null) ? error.getClass().getSimpleName() + ": " + error.getMessage() : "Unknown error";
        return new RailsimSimulationResult(job, Status.FAILURE, message);
    }

    public <T extends PostProcessingResult> void addPostProcessingResult(Class<T> key, T result) {
        this.postProcessingResults.put(key, result);
    }

    public <T extends PostProcessingResult> Optional<T> getPostProcessingResult(Class<T> key) {
        // use the Class object's cast method for a truly safe cast
        return Optional.ofNullable(key.cast(postProcessingResults.get(key)));
    }

    public enum Status {
        SUCCESS, FAILURE
    }
}