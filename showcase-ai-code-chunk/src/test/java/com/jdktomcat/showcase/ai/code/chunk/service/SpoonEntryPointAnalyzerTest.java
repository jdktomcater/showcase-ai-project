package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpoonEntryPointAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractHttpRouteMetadata() throws Exception {
        Path javaFile = tempDir.resolve("src/main/java/com/example/demo/SampleController.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example.demo;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/code")
                class SampleController {

                    @PostMapping("/index")
                    public String fullIndex() {
                        return "ok";
                    }
                }
                """);

        SpoonEntryPointAnalyzer analyzer = new SpoonEntryPointAnalyzer(tempDir.toString());
        List<EntryPoint> entryPoints = analyzer.analyze(javaFile);

        assertEquals(1, entryPoints.size());
        EntryPoint entryPoint = entryPoints.get(0);
        assertEquals("com.example.demo.SampleController#fullIndex()", entryPoint.methodSignature());
        assertTrue(entryPoint.metadata().contains("path=/api/code/index;"));
        assertTrue(entryPoint.metadata().contains("httpMethod=POST;"));
    }

    @Test
    void shouldNotTreatSpringServiceAsRpcEntryPoint() throws Exception {
        Path javaFile = tempDir.resolve("src/main/java/com/example/order/OrderServiceImpl.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example.order;

                import org.springframework.stereotype.Service;

                @Service
                class OrderServiceImpl {

                    public String createOrder(String request) {
                        return request;
                    }
                }
                """);

        SpoonEntryPointAnalyzer analyzer = new SpoonEntryPointAnalyzer(tempDir.toString());
        List<EntryPoint> entryPoints = analyzer.analyze(javaFile);

        assertEquals(0, entryPoints.size());
        assertFalse(entryPoints.stream().anyMatch(entryPoint -> "RPC".equals(entryPoint.type().name())));
    }
}
