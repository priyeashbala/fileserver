package com.pri.fileserver;

import org.springframework.stereotype.Component;

@Component
public class FileServerImpl implements FileServer {
    @Override
    public String connect(String id) {
        String response = "Connecting with id: " + id;
        System.out.println(response);
        return response;
    }
}
