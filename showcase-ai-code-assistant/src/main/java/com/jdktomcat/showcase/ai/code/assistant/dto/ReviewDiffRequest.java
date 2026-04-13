package com.jdktomcat.showcase.ai.code.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReviewDiffRequest {

    @NotBlank
    private String repository;

    private String branch;
    private String sha;
    private String author;
    private String email;
    private String message;
    private String compareUrl;

    @NotBlank
    private String diff;

    private Integer changedFiles;
    private Integer additions;
    private Integer deletions;
}
