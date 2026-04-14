package com.jdktomcat.showcase.ai.code.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AffectedEntryPoint {
    private String id;
    private String type;
    private String route;
    private String httpMethod;
    private String className;
    private String methodName;
    private String methodSignature;
}
