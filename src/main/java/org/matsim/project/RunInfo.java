package org.matsim.project;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RunInfo {
    String executionTimestamp;
    String executedBy;
    String jarVersion;
    String gitBranch;
    String gitCommitId;
    String gitCommitTime;
    String gitTags;
}