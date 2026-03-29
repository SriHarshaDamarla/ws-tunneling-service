package com.tunnel.service.model;

import lombok.Data;

@Data
public class CloseMessage {
    private String type = "close";
    private String id;
}
