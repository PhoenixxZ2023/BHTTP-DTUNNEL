package com.trilead.ssh2;

public interface ServerHostKeyVerifier {
    boolean verifyServerHostKey(String host, int port, String algorithm, byte[] key);
}

