package com.pri.fileserver;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileServerController implements FileServer {
    @Autowired private FileServer fileServerImpl;

    @Override
    public String connect(@RequestParam String id) {
        return fileServerImpl.connect(id);
    }
}
