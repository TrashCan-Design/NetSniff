package com.netsniff.app;

public class Allowed {
    public String raddr = null;
    public int rport = 0;
    
    public Allowed() {
    }
    
    public Allowed(String raddr, int rport) {
        this.raddr = raddr;
        this.rport = rport;
    }
    
    public boolean isForwarded() {
        return raddr != null;
    }
}