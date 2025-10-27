package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.concurrent.*;

@Log4j2
public class RailsimSimulationExecutor {

    private final int workerThreads;
    private final List<PostProcessingTaskFactory> taskFactories;

    public RailsimSimulationExecutor(List<PostProcessingTaskFactory> taskFactories) {
        this(Runtime.getRuntime().availableProcessors(), taskFactories);
    }

    public RailsimSimulationExecutor(int workerThreads, List<PostProcessingTaskFactory> taskFactories) {
        this.workerThreads = Math.max(1, workerThreads);
        this.taskFactories = taskFactories;
    }

    public List<RailsimSimulationResult> runAll(List<RailsimSimulationJob> jobs) {
        log.info("Starting simulator for {} jobs with {} post-processing task types (worker threads: {}).", jobs.size(),
                taskFactories.size(), workerThreads);

        // submit simulation jobs asynchronously
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads);
        List<CompletableFuture<RailsimSimulationResult>> futures = jobs.stream()
                .map(job -> runJobPipelineAsync(job, executor))
                .toList();
        // wait for all jobs to complete and collect their results
        List<RailsimSimulationResult> results = futures.stream().map(CompletableFuture::join).toList();

        shutdownExecutor(executor);
        printSummary(results);

        return results;
    }

    // runs a single simulation job asynchronously and applies the post-processing pipeline.
    private CompletableFuture<RailsimSimulationResult> runJobPipelineAsync(RailsimSimulationJob job,
                                                                           ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
                    log.info("Starting simulation job: {}", job.getRunId());
                    job.run();
                    log.info("Simulation finished successfully: {}", job.getRunId());

                    return RailsimSimulationResult.success(job);
                }, executor)
                // chain the post-processing to run immediately after
                .thenComposeAsync(result -> runPostProcessingAsync(result, executor), executor)
                // handle any exceptions from the entire pipeline
                .exceptionally(ex -> {
                    // unwrap CompletionException to get the root cause
                    Throwable rootCause = (ex instanceof CompletionException) ? ex.getCause() : ex;
                    log.error("Pipeline failed exceptionally for job: {}. Reason: {}", job.getRunId(),
                            rootCause.getMessage());

                    return RailsimSimulationResult.failure(job, rootCause);
                });
    }

    private CompletableFuture<RailsimSimulationResult> runPostProcessingAsync(RailsimSimulationResult successfulResult,
                                                                              Executor executor) {
        CompletableFuture<RailsimSimulationResult> future = CompletableFuture.completedFuture(successfulResult);
        for (PostProcessingTaskFactory factory : taskFactories) {
            future = future.thenApplyAsync(result -> {

                // for each factory, create a new task instance and apply it
                PostProcessingTask<?> task = factory.create();
                try {
                    log.info("Applying post-simulation task [{}] to run {}", task.getKey().name(), result.getRunId());
                    PostProcessingResult taskResult = task.run(result);
                    addResultUntyped(result, task, taskResult);

                    return result;

                } catch (Exception e) {
                    throw new CompletionException("Post-processing task " + task.getKey().name() + " failed.", e);
                }

            }, executor);
        }

        return future;
    }

    @SuppressWarnings("unchecked")
    private <T extends PostProcessingResult> void addResultUntyped(RailsimSimulationResult result,
                                                                   PostProcessingTask<T> task,
                                                                   PostProcessingResult taskResult) {
        result.addPostProcessingResult(task.getKey(), (T) taskResult);
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