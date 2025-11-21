package com.jvmdoctor.backend;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class FileController {
    private static final String ALLOWED_PREFIX = "/tmp"; // restrict downloads to /tmp for now

    @GetMapping("/api/files/download")
    public ResponseEntity<?> download(@RequestParam("path") String filePath) {
        try {
            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            if (!p.startsWith(ALLOWED_PREFIX)) return ResponseEntity.status(403).body("Forbidden");
            File f = p.toFile();
            if (!f.exists()) return ResponseEntity.notFound().build();
            InputStreamResource resource = new InputStreamResource(new FileInputStream(f));
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            if (f.getName().endsWith(".svg")) mediaType = MediaType.valueOf("image/svg+xml");
            if (f.getName().endsWith(".jfr")) mediaType = MediaType.APPLICATION_OCTET_STREAM;
            boolean inline = mediaType.toString().startsWith("image/");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment") + "; filename=\"" + f.getName() + "\"")
                .contentLength(f.length())
                .contentType(mediaType)
                .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
