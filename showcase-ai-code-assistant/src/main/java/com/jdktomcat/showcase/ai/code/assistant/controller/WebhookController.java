package com.jdktomcat.showcase.ai.code.assistant.controller;

import com.jdktomcat.showcase.ai.code.assistant.service.github.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/github")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveWebhook(@RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                                 @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                                 @RequestBody String payload) {
        if (!"push".equals(event)) {
            return ResponseEntity.ok("ignored");
        }
        boolean valid = webhookService.verifySignature(payload, signature);
        if (!valid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
        webhookService.handlePushEvent(payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("accepted");
    }
}
