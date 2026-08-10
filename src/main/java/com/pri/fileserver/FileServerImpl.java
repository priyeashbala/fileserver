package com.pri.fileserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Stream;

@Component
public class FileServerImpl implements FileServer {

    private final Path storageRoot;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom rng = new SecureRandom();

    public FileServerImpl(@Value("${fileserver.storage-dir:#{systemProperties['user.home'] + '/fileserver-storage'}}") String storageDir) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            Files.createDirectories(storageRoot.resolve("common"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage dir: " + storageRoot, e);
        }
    }

    // ---------- whoami ----------

    @Override
    public Map<String, String> whoami(String cookieDeviceId, String name, HttpServletResponse response) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cookieDeviceId != null && isValidId(cookieDeviceId)) {
            Optional<Path> dirOpt = deviceDir(cookieDeviceId);
            if (dirOpt.isPresent() && Files.exists(dirOpt.get())) {
                Path meta = dirOpt.get().resolve(".device.json");
                try {
                    if (Files.exists(meta)) {
                        Map<String, String> m = mapper.readValue(meta.toFile(), Map.class);
                        out.put("deviceId", cookieDeviceId);
                        out.put("name", m.getOrDefault("name", cookieDeviceId));
                        return out;
                    }
                } catch (IOException ignored) { }
            }
        }
        // mint a new device
        String newId = newId();
        String friendly = (name == null || name.isBlank()) ? "device-" + newId.substring(0, 4) : name.trim();
        try {
            Path dir = deviceDir(newId).orElseThrow();
            Files.createDirectories(dir);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", friendly);
            mapper.writeValue(dir.resolve(".device.json").toFile(), m);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create device", e);
        }
        out.put("deviceId", newId);
        out.put("name", friendly);
        return out;
    }

    public String mintCookieAndRedirect(HttpServletResponse response, String deviceId) {
        Cookie c = new Cookie("fs_device", deviceId);
        c.setPath("/");
        c.setMaxAge(60 * 60 * 24 * 365);
        c.setHttpOnly(false); // simple LAN app; JS needs to read it for the UI
        response.addCookie(c);
        return deviceId;
    }

    // ---------- list ----------

    @Override
    public Map<String, Object> list(String deviceId, String path) {
        Path base = deviceDir(deviceId).orElseThrow(this::unauth);
        Path target = resolveWithin(base, path);
        return listDir(target);
    }

    @Override
    public Map<String, Object> listCommon() {
        Path target = storageRoot.resolve("common");
        return listDir(target);
    }

    private Map<String, Object> listDir(Path dir) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!Files.exists(dir)) {
            out.put("entries", entries);
            out.put("error", "not_found");
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", name);
                e.put("isDir", a.isDirectory());
                if (!a.isDirectory()) {
                    e.put("size", a.size());
                }
                e.put("modified", a.lastModifiedTime().toMillis());
                entries.add(e);
            }
        } catch (IOException ex) {
            throw new RuntimeException("List failed: " + ex.getMessage(), ex);
        }
        entries.sort((a, b) -> {
            boolean ad = (boolean) a.get("isDir"), bd = (boolean) b.get("isDir");
            if (ad != bd) return ad ? -1 : 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });
        out.put("entries", entries);
        out.put("path", storageRoot.relativize(dir).toString());
        return out;
    }

    // ---------- upload ----------

    @Override
    public Map<String, Object> upload(String deviceId, String path, List<MultipartFile> files) throws Exception {
        Path base = deviceDir(deviceId).orElseThrow(this::unauth);
        Path dir = resolveWithin(base, path);
        Files.createDirectories(dir);
        return writeFiles(dir, files);
    }

    @Override
    public Map<String, Object> uploadCommon(List<MultipartFile> files) throws Exception {
        Path dir = storageRoot.resolve("common");
        Files.createDirectories(dir);
        return writeFiles(dir, files);
    }

    private Map<String, Object> writeFiles(Path dir, List<MultipartFile> files) throws IOException {
        List<String> saved = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            String original = sanitize(f.getOriginalFilename());
            if (original == null || original.isBlank()) original = "upload-" + System.currentTimeMillis();
            Path target = dir.resolve(original);
            // avoid overwriting silently — append a counter
            int n = 1;
            while (Files.exists(target)) {
                int dot = original.lastIndexOf('.');
                String stem = dot > 0 ? original.substring(0, dot) : original;
                String ext = dot > 0 ? original.substring(dot) : "";
                target = dir.resolve(stem + " (" + (++n) + ")" + ext);
            }
            try (var in = f.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            saved.add(target.getFileName().toString());
        }
        return Map.of("saved", saved, "count", saved.size());
    }

    // ---------- download ----------

    @Override
    public ResponseEntity<Resource> download(String deviceId, HttpServletRequest request) throws Exception {
        Path base = deviceDir(deviceId).orElseThrow(this::unauth);
        String rel = stripMapping(request, "/fileserver/download");
        return serveFile(base, rel);
    }

    @Override
    public ResponseEntity<Resource> downloadCommon(HttpServletRequest request) throws Exception {
        Path base = storageRoot.resolve("common");
        String rel = stripMapping(request, "/fileserver/download/common");
        return serveFile(base, rel);
    }

    private ResponseEntity<Resource> serveFile(Path base, String rel) throws IOException {
        Path target = resolveWithin(base, rel);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return ResponseEntity.notFound().build();
        }
        Resource res = new FileSystemResource(target);
        String filename = URLEncoder.encode(target.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(res);
    }

    // ---------- delete ----------

    @Override
    public Map<String, Object> delete(String deviceId, HttpServletRequest request) throws Exception {
        Path base = deviceDir(deviceId).orElseThrow(this::unauth);
        String rel = stripMapping(request, "/fileserver/delete");
        return doDelete(base, rel);
    }

    @Override
    public Map<String, Object> deleteCommon(HttpServletRequest request) throws Exception {
        Path base = storageRoot.resolve("common");
        String rel = stripMapping(request, "/fileserver/delete/common");
        return doDelete(base, rel);
    }

    private Map<String, Object> doDelete(Path base, String rel) throws IOException {
        Path target = resolveWithin(base, rel);
        if (!Files.exists(target)) return Map.of("deleted", false, "reason", "not_found");
        if (target.getFileName().toString().startsWith(".")) return Map.of("deleted", false, "reason", "protected");
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                if (s.findAny().isPresent()) return Map.of("deleted", false, "reason", "dir_not_empty");
            }
            Files.delete(target);
        } else {
            Files.delete(target);
        }
        return Map.of("deleted", true);
    }

    // ---------- helpers ----------

    private Optional<Path> deviceDir(String deviceId) {
        if (deviceId == null || !isValidId(deviceId)) return Optional.empty();
        return Optional.of(storageRoot.resolve("devices").resolve(deviceId));
    }

    private boolean isValidId(String s) {
        return s != null && s.matches("[a-f0-9]{16}");
    }

    private String newId() {
        byte[] b = new byte[8];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    /** Resolve a user-supplied sub-path against a sandboxed base; throws on escape. */
    private Path resolveWithin(Path base, String sub) {
        Path b = base.toAbsolutePath().normalize();
        Path s = (sub == null || sub.isBlank()) ? b : b.resolve(sub).normalize();
        if (!s.startsWith(b)) throw new IllegalArgumentException("Path escapes sandbox");
        return s;
    }

    private String stripMapping(HttpServletRequest req, String mapping) {
        String uri = req.getRequestURI();
        if (!uri.startsWith(mapping)) throw new IllegalArgumentException("Bad mapping: " + uri);
        String rest = uri.substring(mapping.length());
        if (rest.startsWith("/")) rest = rest.substring(1);
        return URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }

    private String sanitize(String name) {
        if (name == null) return null;
        // strip any path components the client may try to inject
        String n = Paths.get(name).getFileName().toString();
        n = n.replaceAll("[\\\\/]", "_");
        n = n.replaceAll("[\\x00-\\x1F]", "_");
        if (n.isBlank() || n.equals(".") || n.equals("..")) return null;
        return n;
    }

    private RuntimeException unauth() {
        return new RuntimeException("Unknown or invalid device");
    }

    public Path getStorageRoot() { return storageRoot; }
}
