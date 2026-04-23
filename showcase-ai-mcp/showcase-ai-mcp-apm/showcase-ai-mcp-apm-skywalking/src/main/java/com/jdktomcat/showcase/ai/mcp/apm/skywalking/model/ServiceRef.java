package com.jdktomcat.showcase.ai.mcp.apm.skywalking.model;

import java.util.List;

public record ServiceRef(String id, String name, String shortName, String group, List<String> layers) {
}
