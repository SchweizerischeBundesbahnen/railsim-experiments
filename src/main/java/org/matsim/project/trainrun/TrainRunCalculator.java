package org.matsim.project.trainrun;

import ch.sbb.matsim.contrib.railsim.RailsimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.utils.RailsimConfigHelper;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates train travel times using a railsim simulation on an unconstrained network.
 */
@Log4j2
@RequiredArgsConstructor
public final class TrainRunCalculator {

    private final BuildingBlock buildingBlock;
    private final Path outputPath;

    private static void setUnlimitedRailsimCapacity(Scenario scenario) {
        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (link.getAllowedModes().contains("rail")) {
                link.getAttributes().putAttribute("railsimTrainCapacity", 9999);
            }
        }
    }

    private static void setZeroTravelTimes(Scenario scenario) {
        TransitSchedule schedule = scenario.getTransitSchedule();
        Vehicles vehicles = scenario.getTransitVehicles();
        TransitScheduleFactory factory = schedule.getFactory();

        for (TransitLine transitLine : schedule.getTransitLines().values()) {
            List<TransitRoute> originalRoutes = new ArrayList<>(transitLine.getRoutes().values());

            for (TransitRoute originalRoute : originalRoutes) {
                transitLine.removeRoute(originalRoute);

                if (originalRoute.getDepartures().isEmpty()) {
                    log.warn("No departures found for route {}, deleting from schedule...", originalRoute.getId());
                    continue;
                }

                // define route vehicle type
                Set<Id<VehicleType>> vehicleTypeIds = originalRoute.getDepartures()
                        .values()
                        .stream()
                        .map(departure -> vehicles.getVehicles().get(departure.getVehicleId()).getType().getId())
                        .collect(Collectors.toSet());

                if (vehicleTypeIds.size() != 1) {
                    throw new RuntimeException(
                            "Route " + originalRoute.getId() + " has no unique vehicle types assigned: " + vehicleTypeIds);
                }

                VehicleType routeVehicleType = vehicles.getVehicleTypes().get(vehicleTypeIds.iterator().next());

                // set travel times to zero
                List<TransitRouteStop> newStops = new ArrayList<>();
                double cumulativeTime = 0.0;
                List<TransitRouteStop> stops = originalRoute.getStops();
                int stopCount = stops.size();

                for (int i = 0; i < stopCount; i++) {
                    TransitRouteStop originalStop = stops.get(i);

                    double arrivalOffset = originalStop.getArrivalOffset().isDefined() ? originalStop.getArrivalOffset()
                            .seconds() : 0.0;
                    double departureOffset = originalStop.getDepartureOffset()
                            .isDefined() ? originalStop.getDepartureOffset().seconds() : arrivalOffset;
                    double dwellTime = Math.max(0, departureOffset - arrivalOffset);

                    // check minimum stop duration consistency
                    if (originalStop.getMinimumStopDuration() > 0) {
                        double minDuration = originalStop.getMinimumStopDuration();
                        if (Math.abs(minDuration - dwellTime) > 1e-6) {
                            throw new RuntimeException(
                                    "Mismatch between minimumStopDuration (" + minDuration + ") and dwell time (" + dwellTime + ") at stop " + originalStop.getStopFacility()
                                            .getId() + " in route " + originalRoute.getId());
                        }
                    } else {
                        log.warn(
                                "Stop {} in route {} has no minimumStopDuration set. " + "Setting it to dwell time = {} seconds.",
                                originalStop.getStopFacility().getId(), originalRoute.getId(), dwellTime);
                    }

                    double newArrivalTime;
                    double newDepartureTime;

                    // first stop
                    if (i == 0) {
                        newArrivalTime = 0.0;  // typically undefined, but we’ll define it as 0
                        newDepartureTime = 0.0 + dwellTime;
                    }

                    // last stop
                    else if (i == stopCount - 1) {
                        newArrivalTime = cumulativeTime;
                        newDepartureTime = cumulativeTime + dwellTime; // or equal to arrival if no dwell
                    }

                    // intermediate stops
                    else {
                        newArrivalTime = cumulativeTime;
                        newDepartureTime = cumulativeTime + dwellTime;
                    }

                    TransitRouteStop newStop = factory.createTransitRouteStop(originalStop.getStopFacility(),
                            OptionalTime.defined(newArrivalTime), OptionalTime.defined(newDepartureTime));

                    newStop.setAwaitDepartureTime(originalStop.isAwaitDepartureTime());
                    newStop.setAllowAlighting(originalStop.isAllowAlighting());
                    newStop.setAllowBoarding(originalStop.isAllowBoarding());
                    newStop.setMinimumStopDuration(dwellTime);

                    newStops.add(newStop);
                    cumulativeTime = newDepartureTime;
                }

                // create new transit route for replacing original
                TransitRoute updatedRoute = factory.createTransitRoute(originalRoute.getId(), originalRoute.getRoute(),
                        newStops, originalRoute.getTransportMode());

                // remove all vehicles from other departures
                originalRoute.getDepartures()
                        .values()
                        .forEach(departure -> vehicles.removeVehicle(departure.getVehicleId()));

                // create vehicle for new departure
                Id<Vehicle> newVehicleId = Id.create("train_" + originalRoute.getId() + "_0", Vehicle.class);
                Vehicle newVehicle = VehicleUtils.createVehicle(newVehicleId, routeVehicleType);
                vehicles.addVehicle(newVehicle);

                // set one departure at simulation start
                Id<Departure> newDepratureId = Id.create(originalRoute.getId() + "dep_0", Departure.class);
                Departure newDeparture = factory.createDeparture(newDepratureId, 0.);
                newDeparture.setVehicleId(newVehicle.getId());
                updatedRoute.addDeparture(newDeparture);

                transitLine.addRoute(updatedRoute);
            }
        }
    }

    private static void setCalculatedTravelTimes(Scenario scenario,
                                                 Map<Id<Departure>, Map<Id<TransitStopFacility>, Double>> arrivalTimes) {
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        for (TransitLine transitLine : scenario.getTransitSchedule().getTransitLines().values()) {
            List<TransitRoute> routesToUpdate = new ArrayList<>(transitLine.getRoutes().values());

            for (TransitRoute originalRoute : routesToUpdate) {
                Id<Departure> simDepartureId = Id.create(originalRoute.getId() + "dep_0", Departure.class);
                Map<Id<TransitStopFacility>, Double> stopArrivalTimes = arrivalTimes.get(simDepartureId);

                if (stopArrivalTimes == null) {
                    log.warn("No simulation events found for route {}. Skipping update.", originalRoute.getId());
                    continue;
                }

                List<TransitRouteStop> newStops = new ArrayList<>();
                List<TransitRouteStop> originalStops = originalRoute.getStops();

                for (int i = 0; i < originalStops.size(); i++) {
                    TransitRouteStop originalStop = originalStops.get(i);
                    Id<TransitStopFacility> stopFacilityId = originalStop.getStopFacility().getId();

                    // calculate original dwell time
                    double originalArrivalOffset = originalStop.getArrivalOffset()
                            .isDefined() ? originalStop.getArrivalOffset().seconds() : 0.0;
                    double originalDepartureOffset = originalStop.getDepartureOffset()
                            .isDefined() ? originalStop.getDepartureOffset().seconds() : originalArrivalOffset;
                    double dwellTime = originalDepartureOffset - originalArrivalOffset;

                    double newArrivalOffset;

                    if (i == 0) {
                        // first stop always has an arrival offset of 0 in the simulation run
                        newArrivalOffset = 0;
                    } else {
                        Double arrivalTime = stopArrivalTimes.get(stopFacilityId);
                        if (arrivalTime == null) {
                            throw new RuntimeException(
                                    "Missing arrival event for stop " + stopFacilityId + " in route " + originalRoute.getId());
                        }
                        newArrivalOffset = arrivalTime;
                    }

                    double newDepartureOffset = newArrivalOffset + dwellTime;

                    TransitRouteStop newStop = factory.createTransitRouteStop(originalStop.getStopFacility(),
                            OptionalTime.defined(newArrivalOffset), OptionalTime.defined(newDepartureOffset));

                    // preserve all other attributes from the original stop
                    newStop.setAwaitDepartureTime(originalStop.isAwaitDepartureTime());
                    newStop.setAllowAlighting(originalStop.isAllowAlighting());
                    newStop.setAllowBoarding(originalStop.isAllowBoarding());
                    newStop.setMinimumStopDuration(originalStop.getMinimumStopDuration());
                    newStops.add(newStop);
                }

                // create an updated route with the new stops
                TransitRoute updatedRoute = factory.createTransitRoute(originalRoute.getId(), originalRoute.getRoute(),
                        newStops, originalRoute.getTransportMode());

                // copy all departures from the original route to the updated one
                for (Departure dep : originalRoute.getDepartures().values()) {
                    updatedRoute.addDeparture(dep);
                }

                // replace the old route with the updated one
                transitLine.removeRoute(originalRoute);
                transitLine.addRoute(updatedRoute);
            }
        }
    }

    public Scenario run() throws IOException {
        log.info("Starting train run calculation for: {}", buildingBlock.name());
        Config config = createTrainRunCalculationConfig();

        log.info("Modify scenario (unlimited capacity and zero travel times) for train run calculation");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        setUnlimitedRailsimCapacity(scenario);
        setZeroTravelTimes(scenario);

        log.info("Running railsim simulation...");
        Controller controller = ControllerUtils.createController(scenario);
        controller.addOverridingModule(new RailsimModule());
        controller.configureQSimComponents(components -> new RailsimQSimModule().configure(components));
        controller.run();
        RailsimConfigHelper.writeStaticOutputFiles(controller);

        log.info("Processing simulation output events to calculate travel times...");
        Path eventsFile = Paths.get(config.controller().getOutputDirectory())
                .resolve("ITERS")
                .resolve("it.0")
                .resolve(config.controller().getRunId() + ".0.events.xml.gz");
        TrainRunEventHandler eventHandler = new TrainRunEventHandler();
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(eventHandler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile.toString());
        Map<Id<Departure>, Map<Id<TransitStopFacility>, Double>> arrivalTimes =
                eventHandler.getArrivalTimesPerDeparture();

        // Update schedule in-place
        log.info("Updating transit schedule with calculated travel times...");
        setCalculatedTravelTimes(scenario, arrivalTimes);

        Path recalculatedScheduleFile = outputPath.resolve(
                config.controller().getRunId() + ".output_transitSchedule_updated.xml.gz");
        log.info("Writing updated schedule to {}", recalculatedScheduleFile);
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(recalculatedScheduleFile.toString());

        return scenario;
    }

    private Config createTrainRunCalculationConfig() throws IOException {
        Config config = ConfigUtils.loadConfig(ResourceLoader.getPath(buildingBlock.getConfigFilePath()).toString());
        config.controller().setOutputDirectory(outputPath.toString());
        config.controller().setRunId("train_run_calculation");

        // overwrite relative paths from the config file with absolute paths to the extracted resources
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        config.transit()
                .setTransitScheduleFile(ResourceLoader.getPath(buildingBlock.getTransitScheduleFilePath()).toString());
        config.transit().setVehiclesFile(ResourceLoader.getPath(buildingBlock.getVehiclesFilePath()).toString());

        // set railsim specific config options: one iteration, disable unnecessary outputs
        RailsimConfigHelper.configure(config);

        return config;
    }
}