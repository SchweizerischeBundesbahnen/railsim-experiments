package org.matsim.project.analysis;

import lombok.Getter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.ProductType;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import java.util.*;
import java.util.stream.Collectors;

public class MinimumHeadwayEventHandler implements LinkEnterEventHandler, TransitDriverStartsEventHandler, EventHandler {

    private final Map<Id<Link>, LastVehicleInfo> lastVehicleOnLink = new HashMap<>();
    private final Set<Id<Link>> entryLinks;
    private final Map<Id<Vehicle>, CurrentVehicleInfo> currentVehicles = new HashMap<>();
    private final Vehicles vehicles;

    @Getter
    private final List<MinimumHeadwayAnalysis.HeadwayInfo> headwayEvents = new ArrayList<>();

    private final OperationalPlan operationalPlan;
    private final Map<Id<VehicleType>, ProductType> vehicleTypeToProductMap;

    public MinimumHeadwayEventHandler(Scenario scenario, OperationalPlan operationalPlan) {
        this.vehicles = scenario.getTransitVehicles();
        this.operationalPlan = operationalPlan;
        this.entryLinks = scenario.getNetwork()
                .getLinks()
                .values()
                .stream()
                .filter(link -> "true".equalsIgnoreCase(
                        String.valueOf(link.getAttributes().getAttribute("railsimEntry"))))
                .map(Link::getId)
                .collect(Collectors.toSet());

        this.vehicleTypeToProductMap = new HashMap<>();
        for (ProductType pt : ProductType.values()) {
            this.vehicleTypeToProductMap.put(Id.create(pt.name(), VehicleType.class), pt);
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        Vehicle vehicle = this.vehicles.getVehicles().get(event.getVehicleId());
        if (vehicle == null) {
            return;
        }
        currentVehicles.put(event.getVehicleId(),
                new CurrentVehicleInfo(event.getDepartureId(), event.getTransitRouteId(), vehicle.getType().getId()));
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (!entryLinks.contains(event.getLinkId())) {
            return;
        }

        CurrentVehicleInfo currentVehicle = currentVehicles.get(event.getVehicleId());
        if (currentVehicle == null) {
            return;
        }

        LastVehicleInfo previousVehicle = lastVehicleOnLink.get(event.getLinkId());
        double headway = Double.NaN;
        double minHeadway = Double.NaN;
        double previousVehicleTime = Double.NaN;
        Id<Vehicle> previousVehicleId = null;
        Id<VehicleType> previousVehicleTypeId = null;

        if (previousVehicle != null) {
            headway = event.getTime() - previousVehicle.entryTime();
            previousVehicleTime = previousVehicle.entryTime();
            ProductType previousProductType = vehicleTypeToProductMap.get(previousVehicle.vehicleTypeId());
            if (previousProductType != null) {
                minHeadway = operationalPlan.getMinimumHeadway().getOrDefault(previousProductType, 0);
            }
            previousVehicleId = previousVehicle.vehicleId();
            previousVehicleTypeId = previousVehicle.vehicleTypeId();
        }

        MinimumHeadwayAnalysis.HeadwayInfo info = new MinimumHeadwayAnalysis.HeadwayInfo(event.getTime(),
                event.getLinkId(),
                event.getVehicleId(), currentVehicle.departureId(), currentVehicle.routeId(),
                currentVehicle.vehicleTypeId(), headway, minHeadway, previousVehicleTime, previousVehicleId,
                previousVehicleTypeId);
        headwayEvents.add(info);

        lastVehicleOnLink.put(event.getLinkId(),
                new LastVehicleInfo(event.getTime(), event.getVehicleId(), currentVehicle.vehicleTypeId()));
    }

    @Override
    public void reset(int iteration) {
        lastVehicleOnLink.clear();
        currentVehicles.clear();
        headwayEvents.clear();
    }

    private record LastVehicleInfo(double entryTime, Id<Vehicle> vehicleId, Id<VehicleType> vehicleTypeId) {
    }

    private record CurrentVehicleInfo(Id<Departure> departureId, Id<TransitRoute> routeId,
                                      Id<VehicleType> vehicleTypeId) {
    }
}