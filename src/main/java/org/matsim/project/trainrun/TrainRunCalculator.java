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
import org.matsim.core.config.groups.ControllerConfigGroup.EventsFileFormat;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates train travel times using a railsim simulation on an unconstrained network.
 */
@Log4j2
@RequiredArgsConstructor
public final class TrainRunCalculator {

    private final String configPath;
    private final String outputPath;

    public static void main(String[] args) {
        new TrainRunCalculator(args[0], args[1]).run();
    }

    public void run() {
        log.info("Starting train run calculation for: {}", configPath);
        TransitSchedule schedule = calculate();

        log.info("Writing updated schedule to: {}", outputPath);
        new TransitScheduleWriter(schedule).writeFile(outputPath + "/schedule_recalculated.xml");

        log.info("Calculation complete.");
    }

    public TransitSchedule calculate() {
        Config config = createTrainRunCalculationConfig();

        log.info("Modify scenario for train run calculation");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        setUnlimitedRailsimCapacity(scenario);
        setZeroTravelTimes(scenario);

        log.info("Running railsim simulation...");
        Controller controller = ControllerUtils.createController(scenario);
        controller.addOverridingModule(new RailsimModule());
        controller.configureQSimComponents(components -> new RailsimQSimModule().configure(components));
        controller.run();
        log.info("Simulation finished");

        log.info("Processing events to calculate travel times...");
        String eventsFile = config.controller().getOutputDirectory() + "/" + config.controller()
                .getRunId() + ".output_events.xml.gz";
        TrainRunEventHandler eventHandler = new TrainRunEventHandler();
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(eventHandler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile);
        // Map<Id<Departure>, Map<Id<TransitStopFacility>, Double>> arrivalTimes = eventHandler.getArrivalTimesPerDeparture();
        log.info("Event processing complete.");

        // Update schedule in-place
        log.info("Updating transit schedule with calculated travel times...");

        return null;
    }

    private void setUnlimitedRailsimCapacity(Scenario scenario) {
        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (link.getAllowedModes().contains("rail")) {
                link.getAttributes().putAttribute("railsimTrainCapacity", 9999);
            }
        }
    }

    private void setZeroTravelTimes(Scenario scenario) {
        TransitSchedule schedule = scenario.getTransitSchedule();
        Vehicles vehicles = scenario.getTransitVehicles();
        TransitScheduleFactory factory = schedule.getFactory();

        for (TransitLine transitLine : schedule.getTransitLines().values()) {
            List<TransitRoute> originalRoutes = new ArrayList<>(transitLine.getRoutes().values());

            for (TransitRoute originalRoute : originalRoutes) {
                transitLine.removeRoute(originalRoute);

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

                    // check minimumStopDuration consistency
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

                TransitRoute updatedRoute = factory.createTransitRoute(originalRoute.getId(), originalRoute.getRoute(),
                        newStops, originalRoute.getTransportMode());

                // remove all vehicles from other departures
                originalRoute.getDepartures()
                        .values()
                        .forEach(departure -> vehicles.removeVehicle(departure.getVehicleId()));

                // create vehicle for new departure
                Id<Vehicle> newVehicleId = Id.create("veh_" + originalRoute.getId(), Vehicle.class);
                Vehicle newVehicle = VehicleUtils.createVehicle(newVehicleId, routeVehicleType);
                vehicles.addVehicle(newVehicle);

                // set one departure at simulation start
                Id<Departure> newDepratureId = Id.create("dep_" + originalRoute.getId(), Departure.class);
                Departure newDeparture = factory.createDeparture(newDepratureId, 0.);
                newDeparture.setVehicleId(newVehicle.getId());
                updatedRoute.addDeparture(newDeparture);

                transitLine.addRoute(updatedRoute);
            }
        }
    }

    private Config createTrainRunCalculationConfig() {
        Config config = ConfigUtils.loadConfig(configPath);
        config.controller()
                .setOutputDirectory(outputPath + "/output_" + config.controller().getRunId() + "/train_run_calc");
        config.controller().setRunId(config.controller().getRunId());
        config.controller().setLastIteration(0);
        config.controller()
                .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setEventsFileFormats(EnumSet.of(EventsFileFormat.xml));
        return config;
    }
}
