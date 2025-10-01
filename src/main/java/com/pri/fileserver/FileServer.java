package com.pri.fileserver;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/fileserver")
public interface FileServer {
    @PostMapping("/connect")
    String connect(@RequestParam String id);
}
