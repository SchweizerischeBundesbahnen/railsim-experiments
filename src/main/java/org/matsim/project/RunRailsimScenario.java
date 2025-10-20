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
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Example script to run a railsim simulation.
 *
 */
public final class RunRailsimScenario {

    // "C:/devsbb/tmp/railsim-experiments/output_"
    private static final String OUTPUT_DIRECTORY = "results/output_";

    public static void main(String[] args) {

        String configFilename;
        if (args.length != 0) {
            configFilename = args[0];
        } else {
            configFilename = "scenarios/use_case_1/building_block_2/input/config.xml";
//			configFilename = "scenarios/use_case_1/building_block_3/input/config.xml";
//			configFilename = "scenarios/use_case_1/building_block_4/input/config.xml";
        }

        Config config = ConfigUtils.loadConfig(configFilename);
        config.controller()
                .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setOutputDirectory(OUTPUT_DIRECTORY + config.controller().getRunId() + "/");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);

        controler.addOverridingModule(new RailsimModule());
        controler.configureQSimComponents(components -> new RailsimQSimModule().configure(components));

        controler.run();
    }

}
