package com.jdktomcat.showcase.ai.code.chunk.domain;

public record CodeChunk(
        String id,
        String repo,
        String path,
        String language,
        String packageName,
        String className,
        String methodName,
        String symbolType,
        int startLine,
        int endLine,
        String summary,
        String content,
        String chunkHash
) {

    public String toEmbeddingText() {
        return """
                repo: %s
                path: %s
                language: %s
                package: %s
                class: %s
                method: %s
                symbol_type: %s
                lines: %d-%d
                
                summary:
                %s
                
                code:
                %s
                """.formatted(
                safe(repo),
                safe(path),
                safe(language),
                safe(packageName),
                safe(className),
                safe(methodName),
                safe(symbolType),
                startLine,
                endLine,
                safe(summary),
                safe(content)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
