package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CodeLocationRequest(
        @NotBlank String filePath,
        @NotNull @Min(1) Integer line
) {
}
