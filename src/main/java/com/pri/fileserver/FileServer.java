package com.pri.fileserver;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RequestMapping("/fileserver")
public interface FileServer {

    /** Returns the caller's deviceId and friendly name, creating them on first visit. */
    @GetMapping("/whoami")
    Map<String, String> whoami(@CookieValue(value = "fs_device", required = false) String cookieDeviceId,
                               @RequestParam(value = "name", required = false) String name,
                               jakarta.servlet.http.HttpServletResponse response);

    /** Lists files and subfolders under a path inside the caller's device folder. */
    @GetMapping("/list")
    Map<String, Object> list(@CookieValue("fs_device") String deviceId,
                             @RequestParam(value = "path", required = false) String path);

    /** Lists the shared common folder (flat). */
    @GetMapping("/list/common")
    Map<String, Object> listCommon();

    /** Uploads one or more files into the caller's device folder at the given sub-path. */
    @PostMapping("/upload")
    Map<String, Object> upload(@CookieValue("fs_device") String deviceId,
                               @RequestParam(value = "path", required = false) String path,
                               @RequestParam("files") List<MultipartFile> files) throws Exception;

    /** Uploads into the shared common folder (flat). */
    @PostMapping("/upload/common")
    Map<String, Object> uploadCommon(@RequestParam("files") List<MultipartFile> files) throws Exception;

    /** Downloads a file from the caller's device folder. */
    @GetMapping("/download/**")
    ResponseEntity<Resource> download(@CookieValue("fs_device") String deviceId,
                                      jakarta.servlet.http.HttpServletRequest request) throws Exception;

    /** Downloads a file from the shared common folder. */
    @GetMapping("/download/common/**")
    ResponseEntity<Resource> downloadCommon(jakarta.servlet.http.HttpServletRequest request) throws Exception;

    /** Deletes a file (or empty subfolder) from the caller's device folder. */
    @DeleteMapping("/delete/**")
    Map<String, Object> delete(@CookieValue("fs_device") String deviceId,
                               jakarta.servlet.http.HttpServletRequest request) throws Exception;

    /** Deletes a file from the shared common folder. */
    @DeleteMapping("/delete/common/**")
    Map<String, Object> deleteCommon(jakarta.servlet.http.HttpServletRequest request) throws Exception;

    @GetMapping("/hello")
    default String sayHello() {
        return "Hello World";
    }

    // legacy stub — kept so older callers don't 404
    @PostMapping("/connect")
    default String connect(@RequestParam String id) {
        return "Connecting with id: " + id;
    }
}
