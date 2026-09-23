package com.sentinelvoice.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F18 — demo seed/reset. Authenticated as ML_SERVICE via /internal/v2/**.
 * Refused on prod profiles.
 */
@RestController
@RequestMapping("/internal/v2/demo")
public class DemoController {

    private final DemoSeedService demoSeedService;

    public DemoController(DemoSeedService demoSeedService) {
        this.demoSeedService = demoSeedService;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "demo-bank") String tenant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("result", demoSeedService.seed(tenant));
        return body;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("result", demoSeedService.resetDemoTenants());
        return body;
    }

    @ExceptionHandler(DemoSeedException.class)
    public ResponseEntity<Map<String, Object>> handle(DemoSeedException ex) {
        HttpStatus status = "FORBIDDEN".equals(ex.getCode()) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of(
                "code", ex.getCode(),
                "message", ex.getMessage()
        ));
    }
}
