package com.trilead.ssh2;

import java.io.IOException;
import java.net.InetSocketAddress;

public class Connection {
    public Connection(String host, int port) {}
    public ConnectionInfo connect(ServerHostKeyVerifier verifier, int connectTimeout,
                                  int kexTimeout) throws IOException { return null; }
    public boolean authenticateWithPassword(String user, String password)
            throws IOException { return false; }
    public DynamicPortForwarder createDynamicPortForwarder(InetSocketAddress address)
            throws IOException { return null; }
    public void addConnectionMonitor(ConnectionMonitor monitor) {}
    public long ping() throws IOException { return 0; }
    public void close() {}
}

