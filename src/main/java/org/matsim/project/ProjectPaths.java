package org.matsim.project;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
public class ProjectPaths {

    private final Path baseOutputDirectory;
    private final Path useCaseDirectory;
    private final Path buildingBlockDirectory;

    public ProjectPaths(String baseOutputDirectory, BuildingBlock buildingBlock) {
        UseCase useCase = buildingBlock.getUseCase();
        this.baseOutputDirectory = Paths.get(baseOutputDirectory);
        this.useCaseDirectory = this.baseOutputDirectory.resolve(useCase.name().toLowerCase());
        this.buildingBlockDirectory = this.useCaseDirectory.resolve(buildingBlock.name().toLowerCase());
    }

    Path getAndEnsure(Folder folder) throws IOException {
        Path path = buildingBlockDirectory.resolve(folder.getDirectory());
        Files.createDirectories(path);
        return path;
    }

    @RequiredArgsConstructor
    enum Folder {
        TRAIN_RUN_CALCULATION,
        SCHEDULE_SAMPLING,
        SIMULATION_JOB_CONFIG,
        SIMULATION_RUN_OUTPUT,
        ANALYSIS;

        public String getDirectory() {
            return String.format("%02d", this.ordinal() + 1) + "_" + this.name().toLowerCase();
        }
    }
}