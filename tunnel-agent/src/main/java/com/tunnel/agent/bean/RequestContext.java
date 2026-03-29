package com.tunnel.agent.bean;

import lombok.Data;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;

@Data
public class RequestContext {
    private String id;
    private String method;
    private String path;
    private String host;
    private int port;
    Map<String, String> headers;

    private PipedInputStream inputStream;
    private PipedOutputStream outputStream;

    public void setInputStreamFromPipedOutputStream(PipedOutputStream outputStream) {
        try {
            this.inputStream = new PipedInputStream(outputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
