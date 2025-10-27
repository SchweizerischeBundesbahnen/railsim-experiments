package org.matsim.project.simulation;

import lombok.Getter;

import java.nio.file.Path;
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
    private final Map<PostProcessingTask.Key<?>, PostProcessingResult> postProcessingResults = new ConcurrentHashMap<>();

    private RailsimSimulationResult(RailsimSimulationJob job, Status status, String errorMessage) {
        this.job = job;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static RailsimSimulationResult success(RailsimSimulationJob job) {
        return new RailsimSimulationResult(job, Status.SUCCESS, null);
    }

    public static RailsimSimulationResult failure(RailsimSimulationJob job, Throwable error) {
        String message = (error != null) ? error.getClass()
                .getSimpleName() + ": " + error.getMessage() : "Unknown error";
        return new RailsimSimulationResult(job, Status.FAILURE, message);
    }

    public String getRunId() {
        return job.getRunId();
    }

    public Path getOutputDirectory() {
        return job.getOutputDirectory();
    }

    public <T extends PostProcessingResult> void addPostProcessingResult(PostProcessingTask.Key<T> key, T result) {
        this.postProcessingResults.put(key, result);
    }

    @SuppressWarnings("unchecked")
    public <T extends PostProcessingResult> Optional<T> getPostProcessingResult(PostProcessingTask.Key<T> key) {
        return Optional.ofNullable((T) postProcessingResults.get(key));
    }

    public enum Status {
        SUCCESS,
        FAILURE
    }
}