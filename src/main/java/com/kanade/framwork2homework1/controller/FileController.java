package com.kanade.framwork2homework1.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class FileController {

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "com", "bat", "cmd", "sh", "msi", "scr", "vbs", "ps1", "jar", "dll", "reg", "pif"
    );

    private final Path storagePath;

    public FileController(@Value("${file.storage.path}") String storagePath) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storagePath);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create storage directory", e);
        }
    }

    @GetMapping("/files")
    public List<FileInfo> listFiles(@RequestParam(defaultValue = "") String path) {
        Path dir = resolvePath(path);
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid directory");
        }

        List<FileInfo> result = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    result.add(new FileInfo(name, "DIR", 0));
                } else {
                    try {
                        result.add(new FileInfo(name, "FILE", Files.size(entry)));
                    } catch (IOException ignored) {
                        result.add(new FileInfo(name, "FILE", 0));
                    }
                }
            });
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read directory");
        }

        result.sort(Comparator.comparing(FileInfo::type).thenComparing(FileInfo::name));
        return result;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file,
                                             @RequestParam(defaultValue = "") String path) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return ResponseEntity.badRequest().body("No file selected");
        }

        String ext = getExtension(originalName).toLowerCase();
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            return ResponseEntity.badRequest().body("File type not allowed: ." + ext);
        }

        Path dir = resolvePath(path);
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.badRequest().body("Target directory does not exist");
        }

        try {
            Path dest = dir.resolve(originalName);
            file.transferTo(dest);
            return ResponseEntity.ok("Upload successful");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Upload failed");
        }
    }

    @PostMapping("/mkdir")
    public ResponseEntity<String> createFolder(@RequestParam(defaultValue = "") String path,
                                               @RequestParam("name") String name) {
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("Folder name is required");
        }
        if (name.contains("/") || name.contains("\\")) {
            return ResponseEntity.badRequest().body("Invalid folder name");
        }

        Path dir = resolvePath(path);
        Path newDir = dir.resolve(name);
        try {
            Files.createDirectories(newDir);
            return ResponseEntity.ok("Folder created");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Cannot create folder");
        }
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam(defaultValue = "") String path) {
        Path filePath = resolvePath(path);
        if (!Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        String filename = filePath.getFileName().toString();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        Resource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private Path resolvePath(String relative) {
        Path resolved = storagePath.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(storagePath)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return resolved;
    }

    private String getExtension(String filename) {
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i + 1) : "";
    }

    public record FileInfo(String name, String type, long size) {}
}
