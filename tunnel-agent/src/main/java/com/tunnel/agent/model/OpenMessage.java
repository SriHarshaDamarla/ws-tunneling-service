package com.tunnel.agent.model;

import lombok.Data;

import java.util.Map;

@Data
public class OpenMessage {
    private String type = "open";
    private String id;
    private String method;
    private String host;
    private String path;
    private int port;
    private Map<String, String> headers;
}
