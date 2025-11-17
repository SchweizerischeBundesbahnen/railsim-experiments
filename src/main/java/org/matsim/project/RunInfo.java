package org.matsim.project;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RunInfo {
    private final String executionTimestamp;
    private final String executedBy;
    private final String jarVersion;
    private final String gitBranch;
    private final String gitCommitId;
    private final String gitCommitTime;
    private final String gitTags;
}