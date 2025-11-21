package com.jvmdoctor.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    @CrossOrigin(origins = "*")
    @PostMapping("/push")
    public ResponseEntity<?> pushMetrics(@RequestBody String metrics) {
        // For now, just log the metrics. In production, store in database or forward to monitoring system
        System.out.println("Received metrics from agent:");
        System.out.println(metrics);
        return ResponseEntity.ok().build();
    }
}