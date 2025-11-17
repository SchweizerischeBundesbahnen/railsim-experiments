package org.matsim.project.analysis.headway;

import ch.sbb.matsim.contrib.railsim.events.RailsimTrainLeavesLinkEvent;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.ProductType;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import java.util.*;
import java.util.stream.Collectors;

@Log4j2
public class MinimumHeadwayEventHandler implements LinkEnterEventHandler, TransitDriverStartsEventHandler, BasicEventHandler {

    private final Map<Id<Link>, LastVehicleInfo> lastVehicleOnLink = new HashMap<>();
    private final Set<Id<Link>> entryLinks;
    private final Map<Id<Vehicle>, CurrentVehicleInfo> currentVehicles = new HashMap<>();
    private final Vehicles vehicles;

    @Getter
    private final List<HeadwayInfo> headwayEvents = new ArrayList<>();

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
    public void handleEvent(Event event) {
        // note: RailsimTrainLeavesLinkEvent requires custom event mapper registration on the event reader
        if (event instanceof RailsimTrainLeavesLinkEvent) {
            handleRailsimTrainLeavesLink((RailsimTrainLeavesLinkEvent) event);
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

    private void handleRailsimTrainLeavesLink(RailsimTrainLeavesLinkEvent event) {
        if (!entryLinks.contains(event.getLinkId())) {
            return;
        }

        LastVehicleInfo lastInfo = lastVehicleOnLink.get(event.getLinkId());
        if (lastInfo != null && lastInfo.vehicleId().equals(event.getVehicleId())) {
            lastVehicleOnLink.put(event.getLinkId(), lastInfo.withLeaveTime(event.getTime()));
        }
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (!entryLinks.contains(event.getLinkId())) {
            return;
        }

        CurrentVehicleInfo followingVehicle = currentVehicles.get(event.getVehicleId());
        if (followingVehicle == null) {
            return;
        }

        LastVehicleInfo previousVehicle = lastVehicleOnLink.get(event.getLinkId());
        if (previousVehicle == null) {
            lastVehicleOnLink.put(event.getLinkId(),
                    new LastVehicleInfo(event.getTime(), Double.NaN, event.getVehicleId(),
                            followingVehicle.vehicleTypeId()));
            return;
        }

        double minHeadway = Double.NaN;
        ProductType previousProductType = vehicleTypeToProductMap.get(previousVehicle.vehicleTypeId());
        if (previousProductType != null) {
            minHeadway = operationalPlan.getMinimumHeadway().getOrDefault(previousProductType, 0);
        }

        double headwayHeadToHead = event.getTime() - previousVehicle.enterTime();
        double headwayTailToHead = event.getTime() - previousVehicle.leaveTime();

        double violationHeadToHead = Math.max(0, minHeadway - headwayHeadToHead);
        double violationTailToHead = Math.max(0, minHeadway - headwayTailToHead);

        HeadwayInfo info = HeadwayInfo.builder()
                .linkId(event.getLinkId())
                .followingVehicleId(event.getVehicleId())
                .followingVehicleTypeId(followingVehicle.vehicleTypeId())
                .followingVehicleEnterTime(event.getTime())
                .previousVehicleId(previousVehicle.vehicleId())
                .previousVehicleTypeId(previousVehicle.vehicleTypeId())
                .previousVehicleEnterTime(previousVehicle.enterTime())
                .previousVehicleLeaveTime(previousVehicle.leaveTime())
                .headwayHeadToHead(headwayHeadToHead)
                .headwayTailToHead(headwayTailToHead)
                .minimumHeadway(minHeadway)
                .violationHeadToHead(violationHeadToHead)
                .violationTailToHead(violationTailToHead)
                .build();

        headwayEvents.add(info);

        lastVehicleOnLink.put(event.getLinkId(), new LastVehicleInfo(event.getTime(), Double.NaN, event.getVehicleId(),
                followingVehicle.vehicleTypeId()));
    }

    @Override
    public void reset(int iteration) {
        lastVehicleOnLink.clear();
        currentVehicles.clear();
        headwayEvents.clear();
    }

    private record LastVehicleInfo(double enterTime, double leaveTime, Id<Vehicle> vehicleId,
                                   Id<VehicleType> vehicleTypeId) {
        public LastVehicleInfo withLeaveTime(double newLeaveTime) {
            return new LastVehicleInfo(this.enterTime, newLeaveTime, this.vehicleId, this.vehicleTypeId);
        }
    }

    private record CurrentVehicleInfo(Id<Departure> departureId, Id<TransitRoute> routeId,
                                      Id<VehicleType> vehicleTypeId) {
    }
}