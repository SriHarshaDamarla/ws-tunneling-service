package com.tunnel.service.controller;

import com.tunnel.service.dto.CreateForwardRequest;
import com.tunnel.service.dto.ToggleRequest;
import com.tunnel.service.model.Forward;
import com.tunnel.service.registry.AgentRegistry;
import com.tunnel.service.tcp.TcpForwardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ForwardController {
    private final TcpForwardService service;
    private final AgentRegistry agentRegistry;

    @GetMapping("/agents")
    public Set<String> getAgents() {
        return agentRegistry.getAgentIds();
    }

    @GetMapping("/forwards")
    public Collection<Forward> getForwards() {
        return service.getForwards();
    }

    @PostMapping("/forwards")
    public Forward createForward(@RequestBody CreateForwardRequest request) throws IOException {
        return service.createForward(request);
    }

    @PatchMapping("/forwards/{id}")
    public void toggle(@PathVariable String id, @RequestBody ToggleRequest request) throws IOException {
        service.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/forwards/{id}")
    public void deleteForward(@PathVariable String id) {
        service.deleteForward(id);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> handleIOException(IOException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
