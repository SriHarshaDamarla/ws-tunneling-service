package com.tunnel.agent;

import com.tunnel.agent.listener.AgentWebSocketListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TunnelAgent {
    private static final Map<String, String[]> hosts = new ConcurrentHashMap<>();
    public static void main(String[] args) {
        String agentId = null;
        String serverUrl = null;
        String[] targets = null;
        int i = 0;

        while (i < args.length) {
            if ("--agent-id".equals(args[i]) && i + 1 < args.length) {
                agentId = args[i + 1];
                i += 2;
            } else if ("--server-url".equals(args[i]) && i + 1 < args.length) {
                serverUrl = args[i + 1];
                i += 2;
            } else if ("--targets".equals(args[i]) && i + 1 < args.length) {
                targets = getTargetsArray(args[i + 1]);
                i += 2;
            } else {
                System.err.println("Unknown argument: " + args[i]);
                return;
            }
        }

        if (agentId == null || serverUrl == null || targets == null) {
            System.err.println("Usage: java [TunnelAgent/jar] --agent-id <id> --server-url <url> --targets <host1:port,host2,...>");
            return;
        }

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(serverUrl)
                .build();

        WebSocket webSocket = client.newWebSocket(request, new AgentWebSocketListener(agentId));

    }

    private static String[] getTargetsArray(String csvTargets) {
        String[] targets = csvTargets.split(",");
        return Arrays.stream(targets)
                .map(String::trim)
                .map(host -> {
                    if (host.startsWith("s:")) {
                        String newHost = host.replace("s:","");
                        String secureHost = "https://" + newHost;
                        String secureWsHost = "wss://" + newHost;
                        hosts.put(newHost, new String[]{secureHost, secureWsHost});
                        return newHost;
                    } else {
                        String newHost = "http://" + host;
                        String wsHost = "ws://" + host;
                        hosts.put(host, new String[]{newHost, wsHost});
                        return host;
                    }
                })
                .toArray(String[]::new);
    }

    public static String[] getSupportedHosts() {
        hosts.forEach((host, urls) -> System.out.println("Registered Host: " + host + " -> " + Arrays.toString(urls)));
        return hosts.keySet().toArray(new String[0]);
    }

    public static Optional<String> getHostUrl(String receivedHost) {
        String[] urls = hosts.get(receivedHost);
        return urls == null ? Optional.empty() : Optional.of(urls[0]);
    }

    public static Optional<String> getWsUrl(String receivedHost) {
        String[] urls = hosts.get(receivedHost);
        return urls == null ? Optional.empty() : Optional.of(urls[1]);
    }
}
