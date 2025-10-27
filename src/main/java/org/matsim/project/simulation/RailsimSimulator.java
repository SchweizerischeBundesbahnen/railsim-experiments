package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Log4j2
public class RailsimSimulator {

    private final int maxParallelSimulations;
    private final List<PostSimulationTask> postSimulationTasks;

    public RailsimSimulator(List<PostSimulationTask> postSimulationTasks) {
        this(Runtime.getRuntime().availableProcessors(), postSimulationTasks);
    }

    public RailsimSimulator(int maxParallelSimulations, List<PostSimulationTask> postSimulationTasks) {
        this.maxParallelSimulations = Math.max(1, maxParallelSimulations);
        this.postSimulationTasks = postSimulationTasks;
    }

    public List<RailsimSimulationResult> runAll(List<RailsimSimulationJob> jobs) {
        log.info("Starting simulator for {} jobs with {} post-processing tasks (max parallel: {}).", jobs.size(),
                postSimulationTasks.size(), maxParallelSimulations);
        ExecutorService executor = Executors.newFixedThreadPool(maxParallelSimulations);

        List<CompletableFuture<RailsimSimulationResult>> futures = jobs.stream()
                .map(job -> runJobAsync(job, executor))
                .toList();

        List<RailsimSimulationResult> results = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()))
                .join();

        shutdownExecutor(executor);
        printSummary(results);
        return results;
    }

    private CompletableFuture<RailsimSimulationResult> runJobAsync(RailsimSimulationJob job, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
                    log.info("Starting simulation job: {}", job.getRunId());
                    job.run();
                    log.info("Simulation finished successfully: {}", job.getRunId());
                    return RailsimSimulationResult.success(job);
                }, executor)
                .thenComposeAsync(result -> runPostProcessingAsync(result, executor), executor)
                .exceptionally(ex -> RailsimSimulationResult.failure(job, ex));
    }

    private CompletableFuture<RailsimSimulationResult> runPostProcessingAsync(RailsimSimulationResult successfulResult,
                                                                              Executor executor) {
        CompletableFuture<RailsimSimulationResult> future = CompletableFuture.completedFuture(successfulResult);
        for (PostSimulationTask task : postSimulationTasks) {
            future = future.thenApplyAsync(result -> {
                try {
                    log.info("Applying post-simulation task [{}] to run {}", task.getName(), result.getRunId());
                    task.run(result);
                    return result;
                } catch (Exception e) {
                    throw new CompletionException("Post-processing task " + task.getName() + " failed.", e);
                }
            }, executor);
        }
        return future;
    }

    private void printSummary(List<RailsimSimulationResult> results) {
        long successCount = results.stream()
                .filter(r -> r.getStatus() == RailsimSimulationResult.Status.SUCCESS)
                .count();
        log.info("------------------------------------------------------------");
        log.info("SIMULATION RUNNER SUMMARY");
        log.info("------------------------------------------------------------");
        log.info("Total Jobs:      {}", results.size());
        log.info("Successful:      {}", successCount);
        log.info("Failed:          {}", results.size() - successCount);
        results.forEach(result -> {
            if (result.getStatus() != RailsimSimulationResult.Status.SUCCESS) {
                log.error("[FAILURE] {}: {}", result.getRunId(), result.getErrorMessage());
            }
        });
        log.info("------------------------------------------------------------");
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                log.warn("Executor did not terminate in 60 minutes. Forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Executor shutdown was interrupted.", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}