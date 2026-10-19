package com.trilead.ssh2;

public interface ConnectionMonitor {
    void connectionLost(Throwable reason);
    void onReceiveInfo(int type, String message);
}
