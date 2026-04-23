package com.jdktomcat.showcase.ai.code.assistant.service.telegram;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramNotificationServiceTest {

    @Test
    void shouldUseNormalizedTemplateAndAvoidDuplicateCompareOrEntryPoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        TelegramNotificationService service = new TelegramNotificationService(restTemplate);
        ReflectionTestUtils.setField(service, "botToken", "token");
        ReflectionTestUtils.setField(service, "chatId", "123");

        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setBranch("main");
        state.setSha("a64eecb48f248254f2201feb04c318c6535fdb44");
        state.setMessage("日志补充");
        state.setAuthor("yuhata");
        state.setDecision("PASS");
        state.setCompareUrl("https://github.com/jdktomcater/showcase-pay/compare/28bc5e04a399...a64eecb48f24");
        state.setTelegramMessage("""
                结论: PASS
                原因: 代码仅新增一条重复日志，风险低，建议清理以降低日志量。
                影响入口点: HTTP `/api/order/create`
                Compare: https://github.com/jdktomcater/showcase-pay/compare/28bc5e04a399...a64eecb48f24
                """);
        state.setAffectedEntryPoints(List.of(
                new AffectedEntryPoint(
                        "HTTP:com.showcase.pay.order.controller.OrderController#createOrder(com.showcase.pay.order.dto.OrderCreateRequest)",
                        "HTTP",
                        "/api/order/create",
                        "POST",
                        "com.showcase.pay.order.controller.OrderController",
                        "createOrder",
                        "com.showcase.pay.order.controller.OrderController#createOrder(com.showcase.pay.order.dto.OrderCreateRequest)"
                )
        ));

        service.sendCommitReview(state);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(any(String.class), entityCaptor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        HttpEntity<Map<String, Object>> requestEntity = (HttpEntity<Map<String, Object>>) entityCaptor.getValue();
        assertThat(requestEntity).isNotNull();
        String text = String.valueOf(requestEntity.getBody().get("text"));

        assertThat(text).contains("GitHub 提交评审结果");
        assertThat(text).contains("评审意见: 代码仅新增一条重复日志，风险低，建议清理以降低日志量。");
        assertThat(text).contains("影响入口点: HTTP `/api/order/create`");
        assertThat(countOccurrences(text, "Compare:")).isEqualTo(1);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while (true) {
            idx = text.indexOf(needle, idx);
            if (idx < 0) {
                return count;
            }
            count++;
            idx += needle.length();
        }
    }
}
