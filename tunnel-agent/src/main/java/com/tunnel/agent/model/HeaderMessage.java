package com.tunnel.agent.model;

import lombok.Data;

import java.util.Map;

@Data
public class HeaderMessage {
    private String type = "headers";
    private String id;
    private int status;
    private Map<String, String> headers;
}
