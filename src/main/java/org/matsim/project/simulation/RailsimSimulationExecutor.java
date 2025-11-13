package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;
import org.matsim.project.scenario.BuildingBlock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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
        log.info("Starting simulator for {} jobs with {} post-processing task types (worker threads: {}).", jobs.size(),
                taskFactories.size(), workerThreads);

        // create separate thread pools for different workloads
        // fixed-size pool for CPU-bound simulation tasks
        ExecutorService simulationExecutor = Executors.newFixedThreadPool(workerThreads);
        // flexible pool for I/O-bound post-processing tasks
        ExecutorService postProcessingExecutor = Executors.newCachedThreadPool();

        List<CompletableFuture<RailsimSimulationResult>> futures = jobs.stream()
                .map(job -> runJobPipelineAsync(job, simulationExecutor, postProcessingExecutor))
                .toList();

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

    // runs a single simulation job asynchronously and applies the post-processing pipeline
    private CompletableFuture<RailsimSimulationResult> runJobPipelineAsync(RailsimSimulationJob job,
                                                                           ExecutorService simulationExecutor,
                                                                           ExecutorService postProcessingExecutor) {
        // run the simulation task
        return CompletableFuture.supplyAsync(() -> {
                    log.info("Starting simulation job: {}", job.getRunId());
                    job.run();
                    log.info("Simulation finished successfully: {}", job.getRunId());

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
                    log.info("Applying post-processing task for result type [{}] to run {}",
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