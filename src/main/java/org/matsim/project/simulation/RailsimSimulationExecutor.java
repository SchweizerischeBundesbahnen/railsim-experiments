package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;
import org.matsim.project.scenario.BuildingBlock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Log4j2
public class RailsimSimulationExecutor {

    private final int workerThreads;
    private final Map<BuildingBlock, List<PostProcessingTaskFactory>> taskFactories;

    public RailsimSimulationExecutor(Map<BuildingBlock, List<PostProcessingTaskFactory>> taskFactories) {
        this(Runtime.getRuntime().availableProcessors(), taskFactories);
    }

    public RailsimSimulationExecutor(int workerThreads,
                                     Map<BuildingBlock, List<PostProcessingTaskFactory>> taskFactories) {
        this.workerThreads = Math.max(1, workerThreads);
        this.taskFactories = taskFactories;
    }

    public List<RailsimSimulationResult> runAll(List<RailsimSimulationJob> jobs) {
        int totalJobs = jobs.size();
        log.info("Starting simulator for {} jobs (worker threads: {}).", totalJobs, workerThreads);

        // progress tracking init
        final AtomicInteger completedJobs = new AtomicInteger(0);
        final long startTime = System.currentTimeMillis();
        final int logFrequency = Math.max(1, totalJobs / 40);

        // create separate thread pools for different workloads
        // fixed-size pool for CPU-bound simulation tasks
        ExecutorService simulationExecutor = Executors.newFixedThreadPool(workerThreads);
        // flexible pool for I/O-bound post-processing tasks
        ExecutorService postProcessingExecutor = Executors.newCachedThreadPool();

        List<CompletableFuture<RailsimSimulationResult>> futures = jobs.stream().map(job -> {
            CompletableFuture<RailsimSimulationResult> pipelineFuture = runJobPipelineAsync(job, simulationExecutor,
                    postProcessingExecutor);

            // attach a non-blocking action to log progress upon completion of each job
            pipelineFuture.whenComplete((result, throwable) -> {
                int currentCompleted = completedJobs.incrementAndGet();
                // log progress for the first job, last job, and every 5 jobs to avoid spam
                if (currentCompleted == 1 || currentCompleted == totalJobs || currentCompleted % logFrequency == 0) {
                    logProgress(currentCompleted, totalJobs, startTime);
                }
            });
            return pipelineFuture;
        }).toList();

        // wait for all pipelines (simulation and post-processing) to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // all results are ready; collect without blocking
        List<RailsimSimulationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        shutdownExecutor(simulationExecutor);
        shutdownExecutor(postProcessingExecutor);
        printSummary(results);

        return results;
    }

    private void logProgress(int completed, int total, long startTime) {
        long elapsedTimeMs = System.currentTimeMillis() - startTime;
        String percentageStr = String.format("%.1f%%", (double) completed / total * 100.0);

        long avgTimePerJobMs = completed > 0 ? elapsedTimeMs / completed : 0;
        String elapsedTimeStr = formatDuration(elapsedTimeMs);
        String avgTimeStr = String.format("%.2fs/job", avgTimePerJobMs / 1000.0);

        String etaStr;
        if (completed < total) {
            long remainingJobs = total - completed;
            long estimatedRemainingMs = remainingJobs * avgTimePerJobMs;
            etaStr = "ETA: " + formatDuration(estimatedRemainingMs);
        } else {
            etaStr = "Total time: " + elapsedTimeStr;
        }

        log.info("Progress: {}/{} ({}) | Elapsed: {} | {} | {}", completed, total, percentageStr, elapsedTimeStr,
                avgTimeStr, etaStr);
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02dh", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d:%02dmin", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private CompletableFuture<RailsimSimulationResult> runJobPipelineAsync(RailsimSimulationJob job,
                                                                           ExecutorService simulationExecutor,
                                                                           ExecutorService postProcessingExecutor) {
        // run the simulation task
        return CompletableFuture.supplyAsync(() -> {
                    log.debug("Starting simulation job: {}", job.getRunId());
                    job.run();
                    log.debug("Simulation finished successfully: {}", job.getRunId());
                    return RailsimSimulationResult.success(job);
                }, simulationExecutor)
                // chain post-processing tasks
                .thenComposeAsync(result -> runPostProcessingAsync(result, postProcessingExecutor),
                        postProcessingExecutor)
                // handle any exceptions from the entire pipeline
                .exceptionally(ex -> {
                    // unwrap completion exception to get the root cause
                    Throwable rootCause = (ex instanceof CompletionException) ? ex.getCause() : ex;
                    log.error("Pipeline failed exceptionally for job: {}. Reason: {}", job.getRunId(),
                            rootCause.getMessage());

                    return RailsimSimulationResult.failure(job, rootCause);
                });
    }

    private CompletableFuture<RailsimSimulationResult> runPostProcessingAsync(RailsimSimulationResult successfulResult,
                                                                              Executor executor) {
        CompletableFuture<RailsimSimulationResult> future = CompletableFuture.completedFuture(successfulResult);

        // for each building block specific factory, create a new post-processing task instance and apply it
        for (PostProcessingTaskFactory factory : taskFactories.get(successfulResult.getJob().getBuildingBlock())) {
            future = future.thenApplyAsync(result -> {
                PostProcessingTask<?> task = factory.create();
                try {
                    log.debug("Applying post-processing task for result type [{}] to run {}",
                            task.getResultType().getSimpleName(), result.getRunId());
                    PostProcessingResult taskResult = task.run(result);
                    addResultUntyped(result, task, taskResult);

                    return result;

                } catch (Exception e) {
                    throw new CompletionException(
                            "Post-processing task " + task.getResultType().getSimpleName() + " failed.", e);
                }

            }, executor);
        }

        return future;
    }

    @SuppressWarnings("unchecked")
    private <T extends PostProcessingResult> void addResultUntyped(RailsimSimulationResult result,
                                                                   PostProcessingTask<T> task,
                                                                   PostProcessingResult taskResult) {
        result.addPostProcessingResult(task.getResultType(), (T) taskResult);
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

        results.stream()
                .filter(r -> r.getStatus() == RailsimSimulationResult.Status.FAILURE)
                .forEach(result -> log.error("[FAILURE] {}: {}", result.getRunId(), result.getErrorMessage()));

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