package org.matsim.project.simulation;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A container for the complete outcome of a simulation pipeline run, including its
 * status, the original job, and a map for storing results from post-processing tasks.
 */
@Getter
public class RailsimSimulationResult {

    private final RailsimSimulationJob job;
    private final Status status;
    private final String errorMessage;
    private final Map<String, Object> analysisResults = new ConcurrentHashMap<>();

    private RailsimSimulationResult(RailsimSimulationJob job, Status status, String errorMessage) {
        this.job = job;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static RailsimSimulationResult success(RailsimSimulationJob job) {
        return new RailsimSimulationResult(job, Status.SUCCESS, null);
    }

    public static RailsimSimulationResult failure(RailsimSimulationJob job, Throwable error) {
        String message = (error.getCause() != null) ? error.getCause().getMessage() : error.getMessage();
        return new RailsimSimulationResult(job, Status.FAILURE, message);
    }

    public String getRunId() {
        return job.getRunId();
    }

    public Path getOutputDirectory() {
        return job.getOutputDirectory();
    }

    public <T> void addAnalysisResult(String key, T result) {
        this.analysisResults.put(key, result);
    }

    public <T> Optional<T> getAnalysisResult(String key, Class<T> type) {
        return Optional.ofNullable(analysisResults.get(key)).map(type::cast);
    }

    public enum Status {
        SUCCESS,
        FAILURE
    }
}