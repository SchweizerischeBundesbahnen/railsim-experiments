package org.matsim.project.analysis.utilization;

import ch.sbb.matsim.contrib.railsim.events.RailsimLinkStateChangeEvent;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceState;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.*;

public class UtilizationEventHandler implements BasicEventHandler {

    private final Set<Id<Link>> monitoredLinks;
    private final int startTime;
    private final int endTime;

    private final Map<Id<Link>, ResourceState> linkStates = new HashMap<>();
    private final Map<Id<Link>, Double> lastStateChangeTime = new HashMap<>();
    private final Map<Id<Link>, Double> exhaustedDurations = new HashMap<>();

    public UtilizationEventHandler(Network network, int startTime, int endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.monitoredLinks = new HashSet<>();

        // identify links with the marker
        for (Link link : network.getLinks().values()) {
            Object marker = link.getAttributes().getAttribute("railsimUtilizationMonitor");
            if (marker != null && "true".equalsIgnoreCase(marker.toString())) {
                monitoredLinks.add(link.getId());

                // initialize defaults
                linkStates.put(link.getId(), ResourceState.EMPTY);
                lastStateChangeTime.put(link.getId(), 0.0);
                exhaustedDurations.put(link.getId(), 0.0);
            }
        }
    }

    @Override
    public void handleEvent(Event event) {
        if (event instanceof RailsimLinkStateChangeEvent railsimEvent) {
            handleStateChange(railsimEvent);
        }
    }

    private void handleStateChange(RailsimLinkStateChangeEvent event) {
        if (!monitoredLinks.contains(event.getLinkId())) {
            return;
        }

        Id<Link> linkId = event.getLinkId();
        double eventTime = event.getTime();

        updateAccumulator(linkId, eventTime);

        linkStates.put(linkId, event.getState());
        lastStateChangeTime.put(linkId, eventTime);
    }

    /**
     * Calculates time spent in the previous state up to 'currentTime'
     * and adds it to the accumulator if relevant.
     */
    private void updateAccumulator(Id<Link> linkId, double currentTime) {
        ResourceState previousState = linkStates.get(linkId);
        double previousChangeTime = lastStateChangeTime.get(linkId);

        // we only care about counting time if the *previous* state was EXHAUSTED
        boolean wasExhausted = (previousState == ResourceState.EXHAUSTED);

        if (wasExhausted) {
            // calculate overlap between [previousChangeTime, currentTime] and [startTime, endTime]
            double overlapStart = Math.max(previousChangeTime, startTime);
            double overlapEnd = Math.min(currentTime, endTime);

            if (overlapEnd > overlapStart) {
                double addedTime = overlapEnd - overlapStart;
                exhaustedDurations.merge(linkId, addedTime, Double::sum);
            }
        }
    }

    /**
     * Call this after event processing to capture time from the last event until endTime.
     */
    public List<UtilizationInfo> getResults() {
        // finalize all links up to endTime
        for (Id<Link> linkId : monitoredLinks) {
            updateAccumulator(linkId, endTime + 1.0); // +1 ensures we cover up to endTime
        }

        List<UtilizationInfo> results = new ArrayList<>();
        double windowDuration = Math.max(0, endTime - startTime);

        for (Id<Link> linkId : monitoredLinks) {
            double exhausted = exhaustedDurations.getOrDefault(linkId, 0.0);

            // if window is 0, avoid NaN
            double util = (windowDuration > 0) ? (exhausted / windowDuration) : 0.0;

            results.add(UtilizationInfo.builder()
                    .linkId(linkId)
                    .observationTime(windowDuration)
                    .exhaustedTime(exhausted)
                    .utilization(util)
                    .build());
        }

        return results;
    }

    @Override
    public void reset(int iteration) {
        linkStates.clear();
        lastStateChangeTime.clear();
        exhaustedDurations.clear();
    }
}