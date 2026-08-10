package com.pri.fileserver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class FileServerController implements FileServer {

    @Autowired private FileServer fileServerImpl;
    @Autowired private FileServerImpl impl;

    // --- root: serve the HTML UI ---
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        return ResponseEntity.ok(new ClassPathResource("static/index.html"));
    }

    // --- whoami also sets the cookie if a new device was minted ---
    @Override
    public Map<String, String> whoami(@CookieValue(value = "fs_device", required = false) String cookieDeviceId,
                                      @RequestParam(value = "name", required = false) String name,
                                      HttpServletResponse response) {
        Map<String, String> out = fileServerImpl.whoami(cookieDeviceId, name, response);
        String id = out.get("deviceId");
        // Always refresh cookie so a freshly minted id is persisted on the client.
        if (id != null && !id.equals(cookieDeviceId)) {
            Cookie c = new Cookie("fs_device", id);
            c.setPath("/");
            c.setMaxAge(60 * 60 * 24 * 365);
            c.setHttpOnly(false);
            response.addCookie(c);
        }
        return out;
    }

    @Override public Map<String, Object> list(String d, String p)                                 { return fileServerImpl.list(d, p); }
    @Override public Map<String, Object> listCommon()                                              { return fileServerImpl.listCommon(); }
    @Override public Map<String, Object> upload(String d, String p, List<org.springframework.web.multipart.MultipartFile> f) throws Exception { return fileServerImpl.upload(d, p, f); }
    @Override public Map<String, Object> uploadCommon(List<org.springframework.web.multipart.MultipartFile> f) throws Exception { return fileServerImpl.uploadCommon(f); }
    @Override public ResponseEntity<Resource> download(String d, HttpServletRequest r) throws Exception { return fileServerImpl.download(d, r); }
    @Override public ResponseEntity<Resource> downloadCommon(HttpServletRequest r) throws Exception { return fileServerImpl.downloadCommon(r); }
    @Override public Map<String, Object> delete(String d, HttpServletRequest r) throws Exception { return fileServerImpl.delete(d, r); }
    @Override public Map<String, Object> deleteCommon(HttpServletRequest r) throws Exception { return fileServerImpl.deleteCommon(r); }
}
