package com.jdktomcat.showcase.ai.code.assistant.service.impact;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeImpactAnalysisServiceTest {

    @Test
    void shouldExtractPreciseAddedLineNumbersFromUnifiedDiff() {
        CodeImpactAnalysisService service = new CodeImpactAnalysisService(new RestTemplate());
        String patch = """
                @@ -20,6 +20,8 @@ public class OrderServiceImpl {
                     public OrderResponse createOrder(OrderCreateRequest request) {
                +        log.info("create order start, request={}", request);
                         validate(request);
                         Order order = buildOrder(request);
                +        log.info("create order result, orderNo={}", order.getOrderNo());
                         return convertToResponse(order);
                     }
                """;

        @SuppressWarnings("unchecked")
        List<Integer> lines = (List<Integer>) ReflectionTestUtils.invokeMethod(service, "extractChangedLineNumbers", patch);

        assertThat(lines).containsExactly(21, 24);
    }

    @Test
    void shouldFallbackToTypeImpactWhenChangedMethodCannotBeLocated() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CodeImpactAnalysisService service = new CodeImpactAnalysisService(restTemplate);
        ReflectionTestUtils.setField(service, "codeChunkBaseUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "impactEnabled", true);
        ReflectionTestUtils.setField(service, "impactDepth", 2);
        ReflectionTestUtils.setField(service, "maxTypes", 5);
        ReflectionTestUtils.setField(service, "maxItemsPerType", 5);

        CompareResponse compareResponse = new CompareResponse();
        CompareResponse.FileDiff fileDiff = new CompareResponse.FileDiff();
        fileDiff.setFilename("showcase-pay-order/src/main/java/com/showcase/pay/order/service/impl/OrderServiceImpl.java");
        fileDiff.setPatch("");
        compareResponse.setFiles(List.of(fileDiff));

        CodeImpactAnalysisService.EntryPointListResponse entryPointListResponse =
                new CodeImpactAnalysisService.EntryPointListResponse();
        entryPointListResponse.setSuccess(true);
        entryPointListResponse.setCount(1);
        entryPointListResponse.setEntryPoints(List.of(Map.of(
                "id", "HTTP:com.showcase.pay.order.controller.OrderController#create",
                "type", "HTTP",
                "className", "com.showcase.pay.order.controller.OrderController",
                "methodName", "create",
                "methodSignature", "com.showcase.pay.order.controller.OrderController#create(com.showcase.pay.order.api.dto.OrderCreateRequest)",
                "metadata", "path=/api/order/create;httpMethod=POST"
        )));
        when(restTemplate.getForObject(
                eq("http://localhost:8081/api/impact/entry-points"),
                eq(CodeImpactAnalysisService.EntryPointListResponse.class)
        )).thenReturn(entryPointListResponse);

        CodeImpactAnalysisService.DependencyResponse dependencyResponse =
                new CodeImpactAnalysisService.DependencyResponse();
        dependencyResponse.setSuccess(true);
        dependencyResponse.setCount(0);
        dependencyResponse.setDependencies(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-dependencies"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.DependencyResponse.class)
        )).thenReturn(dependencyResponse);

        CodeImpactAnalysisService.ImpactResponse impactResponse =
                new CodeImpactAnalysisService.ImpactResponse();
        impactResponse.setSuccess(true);
        impactResponse.setCount(1);
        impactResponse.setImpacts(List.of(Map.of(
                "fqn", "com.showcase.pay.order.controller.OrderController#create(com.showcase.pay.order.api.dto.OrderCreateRequest)",
                "kind", "METHOD",
                "hops", 1,
                "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/controller/OrderController.java",
                "relationTypes", List.of("CALLS")
        )));
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenReturn(impactResponse);

        CodeImpactAnalysisService.ImpactChainResult impactChainResult =
                new CodeImpactAnalysisService.ImpactChainResult();
        impactChainResult.setSuccess(true);
        impactChainResult.setCount(0);
        impactChainResult.setImpactChain(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/impact/chain"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactChainResult.class)
        )).thenReturn(impactChainResult);

        CodeImpactAnalysisService.ImpactAnalysisResult result =
                service.analyzeImpact("jdktomcater/showcase-pay", compareResponse);

        assertThat(result.affectedEntryPoints()).hasSize(1);
        assertThat(result.affectedEntryPoints().get(0).getRoute()).isEqualTo("/api/order/create");
        assertThat(result.summary()).contains("/api/order/create");
    }

    @Test
    void shouldFallbackToSameModuleHttpEntryPointWhenTypeImpactIsEmpty() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CodeImpactAnalysisService service = new CodeImpactAnalysisService(restTemplate);
        ReflectionTestUtils.setField(service, "codeChunkBaseUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "impactEnabled", true);
        ReflectionTestUtils.setField(service, "impactDepth", 2);
        ReflectionTestUtils.setField(service, "maxTypes", 5);
        ReflectionTestUtils.setField(service, "maxItemsPerType", 5);

        CompareResponse compareResponse = new CompareResponse();
        CompareResponse.FileDiff fileDiff = new CompareResponse.FileDiff();
        fileDiff.setFilename("showcase-pay-order/src/main/java/com/showcase/pay/order/service/impl/OrderServiceImpl.java");
        fileDiff.setPatch("");
        compareResponse.setFiles(List.of(fileDiff));

        CodeImpactAnalysisService.EntryPointListResponse entryPointListResponse =
                new CodeImpactAnalysisService.EntryPointListResponse();
        entryPointListResponse.setSuccess(true);
        entryPointListResponse.setCount(2);
        entryPointListResponse.setEntryPoints(List.of(
                Map.of(
                        "id", "HTTP:com.showcase.pay.order.controller.OrderController#create",
                        "type", "HTTP",
                        "className", "com.showcase.pay.order.controller.OrderController",
                        "methodName", "create",
                        "methodSignature", "com.showcase.pay.order.controller.OrderController#create(com.showcase.pay.order.api.dto.OrderCreateRequest)",
                        "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/controller/OrderController.java",
                        "module", "showcase-pay-order",
                        "metadata", "path=/api/order/create;httpMethod=POST"
                ),
                Map.of(
                        "id", "HTTP:com.showcase.pay.payment.controller.PaymentController#callback",
                        "type", "HTTP",
                        "className", "com.showcase.pay.payment.controller.PaymentController",
                        "methodName", "callback",
                        "methodSignature", "com.showcase.pay.payment.controller.PaymentController#callback()",
                        "filePath", "showcase-pay-payment/src/main/java/com/showcase/pay/payment/controller/PaymentController.java",
                        "module", "showcase-pay-payment",
                        "metadata", "path=/api/payment/callback;httpMethod=POST"
                )
        ));
        when(restTemplate.getForObject(
                eq("http://localhost:8081/api/impact/entry-points"),
                eq(CodeImpactAnalysisService.EntryPointListResponse.class)
        )).thenReturn(entryPointListResponse);

        CodeImpactAnalysisService.DependencyResponse dependencyResponse =
                new CodeImpactAnalysisService.DependencyResponse();
        dependencyResponse.setSuccess(true);
        dependencyResponse.setCount(0);
        dependencyResponse.setDependencies(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-dependencies"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.DependencyResponse.class)
        )).thenReturn(dependencyResponse);

        CodeImpactAnalysisService.ImpactResponse emptyImpactResponse =
                new CodeImpactAnalysisService.ImpactResponse();
        emptyImpactResponse.setSuccess(true);
        emptyImpactResponse.setCount(0);
        emptyImpactResponse.setImpacts(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenReturn(emptyImpactResponse);

        CodeImpactAnalysisService.ImpactChainResult impactChainResult =
                new CodeImpactAnalysisService.ImpactChainResult();
        impactChainResult.setSuccess(true);
        impactChainResult.setCount(0);
        impactChainResult.setImpactChain(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/impact/chain"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactChainResult.class)
        )).thenReturn(impactChainResult);

        CodeImpactAnalysisService.ImpactAnalysisResult result =
                service.analyzeImpact("jdktomcater/showcase-pay", compareResponse);

        assertThat(result.affectedEntryPoints()).hasSize(1);
        assertThat(result.affectedEntryPoints().get(0).getRoute()).isEqualTo("/api/order/create");
    }

    @Test
    void shouldTraceEntryPointThroughInterfaceMethodAliasBeforeFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CodeImpactAnalysisService service = new CodeImpactAnalysisService(restTemplate);
        ReflectionTestUtils.setField(service, "codeChunkBaseUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "impactEnabled", true);
        ReflectionTestUtils.setField(service, "impactDepth", 2);
        ReflectionTestUtils.setField(service, "maxTypes", 5);
        ReflectionTestUtils.setField(service, "maxItemsPerType", 5);

        CompareResponse compareResponse = new CompareResponse();
        CompareResponse.FileDiff fileDiff = new CompareResponse.FileDiff();
        fileDiff.setFilename("showcase-pay-order/src/main/java/com/showcase/pay/order/service/impl/OrderServiceImpl.java");
        fileDiff.setPatch("""
                @@ -55,1 +55,2 @@
                -        return convertToResponse(order);
                +        log.info("Order created: orderNo={}, userId={}", orderNo, request.getUserId());
                +        return convertToResponse(order);
                """);
        compareResponse.setFiles(List.of(fileDiff));

        CodeImpactAnalysisService.EntryPointListResponse entryPointListResponse =
                new CodeImpactAnalysisService.EntryPointListResponse();
        entryPointListResponse.setSuccess(true);
        entryPointListResponse.setCount(3);
        entryPointListResponse.setEntryPoints(List.of(
                Map.of(
                        "id", "HTTP:com.showcase.pay.order.controller.OrderController#createOrder",
                        "type", "HTTP",
                        "className", "com.showcase.pay.order.controller.OrderController",
                        "methodName", "createOrder",
                        "methodSignature", "com.showcase.pay.order.controller.OrderController#createOrder(com.showcase.pay.order.dto.OrderCreateRequest)",
                        "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/controller/OrderController.java",
                        "module", "showcase-pay-order",
                        "metadata", "path=/api/order/create;httpMethod=POST"
                ),
                Map.of(
                        "id", "HTTP:com.showcase.pay.order.controller.OrderController#cancelOrder",
                        "type", "HTTP",
                        "className", "com.showcase.pay.order.controller.OrderController",
                        "methodName", "cancelOrder",
                        "methodSignature", "com.showcase.pay.order.controller.OrderController#cancelOrder(java.lang.String)",
                        "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/controller/OrderController.java",
                        "module", "showcase-pay-order",
                        "metadata", "path=/api/order/cancel;httpMethod=POST"
                ),
                Map.of(
                        "id", "HTTP:com.showcase.pay.order.controller.OrderHealthController#health",
                        "type", "HTTP",
                        "className", "com.showcase.pay.order.controller.OrderHealthController",
                        "methodName", "health",
                        "methodSignature", "com.showcase.pay.order.controller.OrderHealthController#health()",
                        "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/controller/OrderHealthController.java",
                        "module", "showcase-pay-order",
                        "metadata", "path=/api/order/health;httpMethod=GET"
                )
        ));
        when(restTemplate.getForObject(
                eq("http://localhost:8081/api/impact/entry-points"),
                eq(CodeImpactAnalysisService.EntryPointListResponse.class)
        )).thenReturn(entryPointListResponse);

        CodeImpactAnalysisService.LocationResponse locationResponse =
                new CodeImpactAnalysisService.LocationResponse();
        locationResponse.setSuccess(true);
        locationResponse.setCount(1);
        locationResponse.setNodes(List.of(Map.of(
                "type", "METHOD",
                "fqn", "com.showcase.pay.order.service.impl.OrderServiceImpl#createOrder(com.showcase.pay.order.dto.OrderCreateRequest)"
        )));
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/location"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.LocationResponse.class)
        )).thenReturn(locationResponse);

        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenAnswer(invocation -> {
            HttpEntity<?> request = invocation.getArgument(1);
            Object body = request.getBody();
            String methodFqn = body == null
                    ? ""
                    : (String) ReflectionTestUtils.invokeMethod(body, "methodFqn");

            CodeImpactAnalysisService.ImpactResponse impactResponse =
                    new CodeImpactAnalysisService.ImpactResponse();
            impactResponse.setSuccess(true);
            if (methodFqn != null && methodFqn.contains("OrderService#createOrder")) {
                impactResponse.setCount(1);
                impactResponse.setImpacts(List.of(Map.of(
                        "fqn", "com.showcase.pay.order.controller.OrderController#createOrder(com.showcase.pay.order.dto.OrderCreateRequest)",
                        "hops", 1
                )));
            } else {
                impactResponse.setCount(0);
                impactResponse.setImpacts(List.of());
            }
            return impactResponse;
        });

        CodeImpactAnalysisService.DependencyResponse dependencyResponse =
                new CodeImpactAnalysisService.DependencyResponse();
        dependencyResponse.setSuccess(true);
        dependencyResponse.setCount(1);
        dependencyResponse.setDependencies(List.of(Map.of(
                "fqn", "com.showcase.pay.order.service.OrderService",
                "kind", "INTERFACE",
                "hops", 1,
                "filePath", "showcase-pay-order/src/main/java/com/showcase/pay/order/service/OrderService.java",
                "relationTypes", List.of("IMPLEMENTS")
        )));
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-dependencies"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.DependencyResponse.class)
        )).thenReturn(dependencyResponse);

        CodeImpactAnalysisService.ImpactResponse emptyTypeImpactResponse =
                new CodeImpactAnalysisService.ImpactResponse();
        emptyTypeImpactResponse.setSuccess(true);
        emptyTypeImpactResponse.setCount(0);
        emptyTypeImpactResponse.setImpacts(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenReturn(emptyTypeImpactResponse);

        CodeImpactAnalysisService.ImpactChainResult impactChainResult =
                new CodeImpactAnalysisService.ImpactChainResult();
        impactChainResult.setSuccess(true);
        impactChainResult.setCount(0);
        impactChainResult.setImpactChain(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/impact/chain"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactChainResult.class)
        )).thenReturn(impactChainResult);

        CodeImpactAnalysisService.ImpactAnalysisResult result =
                service.analyzeImpact("jdktomcater/showcase-pay", compareResponse);

        assertThat(result.affectedEntryPoints()).hasSize(1);
        assertThat(result.affectedEntryPoints().get(0).getRoute()).isEqualTo("/api/order/create");
        assertThat(result.summary())
                .contains("/api/order/create")
                .doesNotContain("/api/order/cancel")
                .doesNotContain("/api/order/health");
    }

    @Test
    void shouldMatchEntryPointByCallGraphWhenMethodSignatureUsesDotStyle() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CodeImpactAnalysisService service = new CodeImpactAnalysisService(restTemplate);
        ReflectionTestUtils.setField(service, "codeChunkBaseUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "impactEnabled", true);
        ReflectionTestUtils.setField(service, "impactDepth", 2);
        ReflectionTestUtils.setField(service, "maxTypes", 5);
        ReflectionTestUtils.setField(service, "maxItemsPerType", 5);

        CompareResponse compareResponse = new CompareResponse();
        CompareResponse.FileDiff fileDiff = new CompareResponse.FileDiff();
        fileDiff.setFilename("foo-module/src/main/java/com/acme/service/InternalService.java");
        fileDiff.setPatch("""
                @@ -1,1 +1,2 @@
                 public class InternalService {
                +    void doWork() {}
                 }
                """);
        compareResponse.setFiles(List.of(fileDiff));

        CodeImpactAnalysisService.EntryPointListResponse entryPointListResponse =
                new CodeImpactAnalysisService.EntryPointListResponse();
        entryPointListResponse.setSuccess(true);
        entryPointListResponse.setCount(1);
        entryPointListResponse.setEntryPoints(List.of(Map.of(
                "id", "HTTP:com.acme.api.EntryController#handle",
                "type", "HTTP",
                "className", "com.acme.api.EntryController",
                "methodName", "handle",
                "methodSignature", "com.acme.api.EntryController#handle()",
                "filePath", "bar-module/src/main/java/com/acme/api/EntryController.java",
                "module", "bar-module",
                "metadata", "path=/api/entry;httpMethod=POST"
        )));
        when(restTemplate.getForObject(
                eq("http://localhost:8081/api/impact/entry-points"),
                eq(CodeImpactAnalysisService.EntryPointListResponse.class)
        )).thenReturn(entryPointListResponse);

        CodeImpactAnalysisService.LocationResponse locationResponse =
                new CodeImpactAnalysisService.LocationResponse();
        locationResponse.setSuccess(true);
        locationResponse.setCount(1);
        locationResponse.setNodes(List.of(Map.of(
                "type", "METHOD",
                "fqn", "com.acme.service.InternalService.doWork()"
        )));
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/location"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.LocationResponse.class)
        )).thenReturn(locationResponse);

        CodeImpactAnalysisService.ImpactResponse methodImpactResponse =
                new CodeImpactAnalysisService.ImpactResponse();
        methodImpactResponse.setSuccess(true);
        methodImpactResponse.setCount(1);
        methodImpactResponse.setImpacts(List.of(Map.of(
                "fqn", "com.acme.api.EntryController.handle()",
                "hops", 1
        )));
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenReturn(methodImpactResponse);

        CodeImpactAnalysisService.DependencyResponse dependencyResponse =
                new CodeImpactAnalysisService.DependencyResponse();
        dependencyResponse.setSuccess(true);
        dependencyResponse.setCount(0);
        dependencyResponse.setDependencies(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-dependencies"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.DependencyResponse.class)
        )).thenReturn(dependencyResponse);

        CodeImpactAnalysisService.ImpactResponse emptyTypeImpactResponse =
                new CodeImpactAnalysisService.ImpactResponse();
        emptyTypeImpactResponse.setSuccess(true);
        emptyTypeImpactResponse.setCount(0);
        emptyTypeImpactResponse.setImpacts(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/code/type-impact"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactResponse.class)
        )).thenReturn(emptyTypeImpactResponse);

        CodeImpactAnalysisService.ImpactChainResult impactChainResult =
                new CodeImpactAnalysisService.ImpactChainResult();
        impactChainResult.setSuccess(true);
        impactChainResult.setCount(0);
        impactChainResult.setImpactChain(List.of());
        when(restTemplate.postForObject(
                eq("http://localhost:8081/api/impact/chain"),
                any(HttpEntity.class),
                eq(CodeImpactAnalysisService.ImpactChainResult.class)
        )).thenReturn(impactChainResult);

        CodeImpactAnalysisService.ImpactAnalysisResult result =
                service.analyzeImpact("jdktomcater/showcase-pay", compareResponse);

        assertThat(result.affectedEntryPoints()).hasSize(1);
        assertThat(result.affectedEntryPoints().get(0).getRoute()).isEqualTo("/api/entry");
        assertThat(result.summary()).contains("/api/entry");
    }
}
