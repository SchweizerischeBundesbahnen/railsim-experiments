package org.matsim.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Getter;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.sampling.strategy.EvenIntervalDepartureSampling;
import org.matsim.project.sampling.strategy.RandomDepartureSampling;
import org.matsim.project.scenario.BuildingBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Builder(toBuilder = true)
@JsonDeserialize(builder = ProjectConfig.ProjectConfigBuilder.class)
public class ProjectConfig {

    @Builder.Default
    private final String outputDirectory = "results"; // ignored by git

    @Builder.Default
    private final boolean overwriteOutput = false;

    @Builder.Default
    private final long seed = 123L;

    @Builder.Default
    private final int samplesPerSubvariant = 100;

    @Builder.Default
    private final DepartureSampling departureSampling = DepartureSampling.RANDOM;

    @Builder.Default
    private final List<BuildingBlock> buildingBlocks = List.of(BuildingBlock.values());

    // private constructor with validation for the builder
    private ProjectConfig(String outputDirectory, boolean overwriteOutput, long seed, int samplesPerSubvariant,
                          DepartureSampling departureSampling, List<BuildingBlock> buildingBlocks) {

        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("Output directory must be specified.");
        }

        this.outputDirectory = outputDirectory;
        this.seed = seed;
        this.samplesPerSubvariant = samplesPerSubvariant;
        this.departureSampling = departureSampling;
        this.buildingBlocks = buildingBlocks;
        this.overwriteOutput = overwriteOutput;

        validateNoDuplicateBuildingBlocks();
    }

    @JsonIgnore
    public DepartureSamplingStrategy getDepartureSamplingStrategy() {
        return departureSampling.create();
    }

    private void validateNoDuplicateBuildingBlocks() {
        if (this.buildingBlocks == null || this.buildingBlocks.isEmpty()) {
            return;
        }

        Set<BuildingBlock> uniqueBuildingBlocks = new HashSet<>(this.buildingBlocks);
        if (uniqueBuildingBlocks.size() < this.buildingBlocks.size()) {
            List<String> duplicates = this.buildingBlocks.stream()
                    .collect(Collectors.groupingBy(bb -> bb, Collectors.counting()))
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(entry -> entry.getKey().name())
                    .toList();
            throw new IllegalArgumentException(
                    "Configuration error: Duplicate building blocks found. Duplicates: " + duplicates);
        }
    }

    public enum DepartureSampling {
        RANDOM,
        EVEN_INTERVAL;

        public DepartureSamplingStrategy create() {
            return switch (this) {
                case RANDOM -> new RandomDepartureSampling();
                case EVEN_INTERVAL -> new EvenIntervalDepartureSampling();
            };
        }
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class ProjectConfigBuilder {
    }
}