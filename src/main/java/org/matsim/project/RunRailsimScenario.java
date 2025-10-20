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

import org.matsim.project.trainrun.TrainRunCalculator;

/**
 * Example script to run a railsim simulation.
 *
 */
public final class RunRailsimScenario {

    private static final String OUTPUT_DIRECTORY = "results";

    public static void main(String[] args) {

        String configFilename;
        if (args.length != 0) {
            configFilename = args[0];
        } else {
            configFilename = "scenarios/use_case_1/building_block_2/input/config.xml";
//			configFilename = "scenarios/use_case_1/building_block_3/input/config.xml";
//			configFilename = "scenarios/use_case_1/building_block_4/input/config.xml";
        }

        new TrainRunCalculator(configFilename, OUTPUT_DIRECTORY).run();

        /*
        Config config = ConfigUtils.loadConfig(configFilename);
        config.controller()
                .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setOutputDirectory(OUTPUT_DIRECTORY + config.controller().getRunId() + "/");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);

        controler.addOverridingModule(new RailsimModule());
        controler.configureQSimComponents(components -> new RailsimQSimModule().configure(components));

        controler.run();

         */
    }

}
