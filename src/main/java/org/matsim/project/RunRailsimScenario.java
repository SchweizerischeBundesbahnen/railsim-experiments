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
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.trainrun.TrainRunCalculator;
import org.matsim.project.utils.ResourceLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RunRailsimScenario {

    private static final String OUTPUT_DIRECTORY = "results";

    private static final BuildingBlock BUILDING_BLOCK = BuildingBlock.UC1_BB2;
    private static final UseCase USE_CASE = BUILDING_BLOCK.getUseCase();

    public static void main(String[] args) throws IOException {

        Path outputPath = Paths.get(OUTPUT_DIRECTORY);
        Path configFile = ResourceLoader.getPath(BUILDING_BLOCK.getConfigFilePath());
        Path operationalPlanPath = ResourceLoader.getPath(USE_CASE.getOperationalPlanPath());

        // train run calculation for template schedule
        Scenario template = new TrainRunCalculator(configFile, outputPath).run();

        // load train volumes per type and direction
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);
    }

}
