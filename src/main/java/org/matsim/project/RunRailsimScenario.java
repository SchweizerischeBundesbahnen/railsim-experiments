/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2023 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.project;

import ch.sbb.matsim.contrib.railsim.RailsimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.sampling.StatefulScheduleSampler;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.sampling.strategy.RandomDepartureSampling;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.*;
import org.matsim.project.trainrun.TrainRunCalculator;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Log4j2
public final class RunRailsimScenario {

    private static final String OUTPUT_DIRECTORY = "results";
    private static final int N_SIMULATIONS = 3;
    private static final long SEED = 123;
    private static final DepartureSamplingStrategy DEPARTURE_SAMPLING_STRATEGY = new RandomDepartureSampling();

    private static final BuildingBlock BUILDING_BLOCK = BuildingBlock.UC1_BB2;

    public static void main(String[] args) throws IOException {

        UseCase useCase = BUILDING_BLOCK.getUseCase();
        Path outputPath = Paths.get(OUTPUT_DIRECTORY)
                .resolve(useCase.name().toLowerCase())
                .resolve(BUILDING_BLOCK.name().toLowerCase());
        Path configFile = ResourceLoader.getPath(BUILDING_BLOCK.getConfigFilePath());
        Path operationalPlanPath = ResourceLoader.getPath(useCase.getOperationalPlanPath());

        // ensure directory
        Files.createDirectories(outputPath);

        // train run calculation for template schedule
        Path trainRunCalculationOutputPath = outputPath.resolve(ProjectStructure.TRAIN_RUN_CALCULATION.getDirectory());
        Scenario template = new TrainRunCalculator(configFile, trainRunCalculationOutputPath).run();

        // load train volumes per type and direction
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);

        // sample schedules
        Path simulationJobPath = outputPath.resolve(ProjectStructure.SIMULATION_JOBS.getDirectory());
        Files.createDirectories(simulationJobPath);
        List<Path> simJobs = new ArrayList<>();
        for (OperationMode operationMode : operationalPlan.getOperationModes()) {
            for (Variant variant : operationMode.getVariants()) {
                for (SubVariant subVariant : variant.getSubVariants()) {
                    Path simulationOutputPath = outputPath.resolve(ProjectStructure.SIMULATION_OUTPUT.getDirectory())
                            .resolve(subVariant.getId().toLowerCase());
                    Files.createDirectories(simulationOutputPath);
                    StatefulScheduleSampler sampler = new StatefulScheduleSampler(SEED, template, subVariant);
                    for (int i = 0; i < N_SIMULATIONS; i++) {
                        StatefulScheduleSampler.Sample sample = sampler.sample(DEPARTURE_SAMPLING_STRATEGY);

                        // save to building block directory
                        Path sampleOutputPath = outputPath.resolve(ProjectStructure.SCHEDULE_SAMPLING.getDirectory())
                                .resolve(subVariant.getId().toLowerCase())
                                .resolve("sample_" + i);
                        Files.createDirectories(sampleOutputPath);

                        Path schedulePath = sampleOutputPath.resolve("schedule.xml");
                        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());

                        Path vehiclePath = sampleOutputPath.resolve("vehicles.xml");
                        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

                        // configure new simulation job
                        String runId = BUILDING_BLOCK.name().toLowerCase() + "_" + subVariant.getId()
                                .toLowerCase() + "_sample_" + i;
                        Config config = ConfigUtils.loadConfig(configFile.toString());
                        config.controller().setRunId(runId);
                        config.network()
                                .setInputFile(ResourceLoader.getPath(BUILDING_BLOCK.getNetworkFilePath()).toString());
                        config.transit().setTransitScheduleFile(simulationJobPath.relativize(schedulePath).toString());
                        config.transit().setVehiclesFile(simulationJobPath.relativize(vehiclePath).toString());
                        config.controller().setOutputDirectory(simulationOutputPath.resolve("sample_" + i).toString());
                        config.controller()
                                .setOverwriteFileSetting(
                                        OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
                        config.controller().setLastIteration(0);

                        // write new sim job
                        Path configFilePath = simulationJobPath.resolve(runId + ".config.xml");
                        ConfigUtils.writeConfig(config, configFilePath.toString());
                        simJobs.add(configFilePath);
                    }
                }
            }
        }

        log.info("---------------- STARTING SIMS ---------------");

        // run simulations
        for (Path simJob : simJobs) {
            Config config = ConfigUtils.loadConfig(simJob.toString());
            Scenario scenario = ScenarioUtils.loadScenario(config);
            Controller controller = ControllerUtils.createController(scenario);
            controller.addOverridingModule(new RailsimModule());
            controller.configureQSimComponents(components -> new RailsimQSimModule().configure(components));
            controller.run();
        }
    }
}
