package com.jvmdoctor.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(TaskManager.list().keySet());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") long id) {
        boolean ok = TaskManager.cancel(id);
        return ok ? ResponseEntity.ok(Map.of("cancelled", true)) : ResponseEntity.notFound().build();
    }
}
