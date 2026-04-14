package com.jdktomcat.showcase.ai.code.assistant.service.impact;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
