package com.tunnel.agent;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * SocketFactory that enables TCP_NODELAY (disables Nagle) on every socket okhttp creates.
 * The WebSocket transport is the ONLY socket that crosses the network; without this it
 * suffers Nagle + delayed-ACK stalls on a real LAN/WAN (invisible on localhost).
 */
public class NoDelaySocketFactory extends SocketFactory {

    private final SocketFactory delegate = SocketFactory.getDefault();

    private Socket configure(Socket s) throws SocketException {
        s.setTcpNoDelay(true);
        return s;
    }

    @Override
    public Socket createSocket() throws IOException {
        return configure(delegate.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return configure(delegate.createSocket(address, port, localAddress, localPort));
    }
}
