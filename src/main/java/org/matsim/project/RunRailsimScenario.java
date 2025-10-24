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

import org.matsim.api.core.v01.Scenario;
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
        Scenario template = new TrainRunCalculator(configFile, outputPath.resolve("01_train_run_calculation")).run();

        // load train volumes per type and direction
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);

        // sample schedules
        for (OperationMode operationMode : operationalPlan.getOperationModes()) {
            for (Variant variant : operationMode.getVariants()) {
                for (SubVariant subVariant : variant.getSubVariants()) {
                    StatefulScheduleSampler sampler = new StatefulScheduleSampler(SEED, template, subVariant);
                    for (int i = 0; i < N_SIMULATIONS; i++) {
                        StatefulScheduleSampler.Sample sample = sampler.sample(DEPARTURE_SAMPLING_STRATEGY);

                        // save to building block directory
                        Path sampleOutputPath = outputPath.resolve("02_schedule_sampling")
                                .resolve(subVariant.getId().toLowerCase())
                                .resolve("sample_" + i);
                        Files.createDirectories(sampleOutputPath);

                        new TransitScheduleWriter(sample.schedule()).writeFile(
                                sampleOutputPath.resolve("schedule.xml").toString());
                        new MatsimVehicleWriter(sample.vehicles()).writeFile(
                                sampleOutputPath.resolve("vehicles.xml").toString());
                    }
                }
            }
        }
    }
}
