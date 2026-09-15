package com.tunnel.agent.model;

import lombok.Data;

@Data
public class TcpOpen {
    private String type = "tcp-open";
    private int connId;
    private String host;
    private int port;

}
