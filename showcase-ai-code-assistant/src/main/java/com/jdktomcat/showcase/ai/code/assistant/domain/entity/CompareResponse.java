package com.jdktomcat.showcase.ai.code.assistant.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompareResponse {

    @JsonProperty("total_commits")
    private Integer totalCommits;

    private List<FileDiff> files;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileDiff {
        private String filename;
        private String status;
        private Integer additions;
        private Integer deletions;
        private Integer changes;
        private String patch;
    }
}
