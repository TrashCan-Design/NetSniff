package com.netsniff.app;

import java.nio.ByteBuffer;


public class PacketUtils {
    
   // Calculate IP header checksum
    public static short calculateIPChecksum(ByteBuffer buffer, int start) {
        int sum = 0;
        buffer.position(start);
        
        // IP header is always 20 bytes minimum
        for (int i = 0; i < 10; i++) {
            int word = buffer.getShort() & 0xFFFF;
            sum += word;
        }
        
        // Add overflow back
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        
        return (short) ~sum;
    }
    
    // Calculate TCP checksum including pseudo-header
    public static short calculateTCPChecksum(ByteBuffer buffer, int ipStart, int tcpStart, int tcpLength) {
        long sum = 0;
        
        // Pseudo-header: source IP
        buffer.position(ipStart + 12);
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        
        // Pseudo-header: destination IP
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        
        // Pseudo-header: protocol (TCP = 6)
        sum += 6;
        
        // Pseudo-header: TCP length
        sum += tcpLength;
        
        // TCP header and data
        buffer.position(tcpStart);
        int words = tcpLength / 2;
        for (int i = 0; i < words; i++) {
            sum += buffer.getShort() & 0xFFFF;
        }
        
        // Handle odd byte
        if (tcpLength % 2 != 0) {
            sum += (buffer.get() & 0xFF) << 8;
        }
        
        // Add overflow back
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        
        return (short) ~sum;
    }
    
    
    // Calculate UDP checksum including pseudo-header

    public static short calculateUDPChecksum(ByteBuffer buffer, int ipStart, int udpStart, int udpLength) {
        long sum = 0;
        
        // Pseudo-header: source IP
        buffer.position(ipStart + 12);
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        
        // Pseudo-header: destination IP
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        sum += (buffer.get() & 0xFF) << 8;
        sum += buffer.get() & 0xFF;
        
        // Pseudo-header: protocol UDP = 17
        sum += 17;
        
        // Pseudo-header: UDP length
        sum += udpLength;
        
        // UDP header and data
        buffer.position(udpStart);
        int words = udpLength / 2;
        for (int i = 0; i < words; i++) {
            sum += buffer.getShort() & 0xFFFF;
        }
        
        // Handle odd byte
        if (udpLength % 2 != 0) {
            sum += (buffer.get() & 0xFF) << 8;
        }
        
        // Add overflow back
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        
        short checksum = (short) ~sum;
        // UDP checksum of 0 means no checksum
        return checksum == 0 ? (short) 0xFFFF : checksum;
    }
    
    // Extract IP addresses from packet
    public static class IPAddresses {
        public byte[] sourceIP = new byte[4];
        public byte[] destIP = new byte[4];
    }
    
    public static IPAddresses extractIPAddresses(ByteBuffer packet) {
        IPAddresses addr = new IPAddresses();
        packet.position(12);
        packet.get(addr.sourceIP);
        packet.get(addr.destIP);
        return addr;
    }
    
    // Extract ports from TCP/UDP packet

    public static class Ports {
        public int sourcePort;
        public int destPort;
    }
    
    public static Ports extractPorts(ByteBuffer packet, int headerStart) {
        Ports ports = new Ports();
        packet.position(headerStart);
        ports.sourcePort = packet.getShort() & 0xFFFF;
        ports.destPort = packet.getShort() & 0xFFFF;
        return ports;
    }
}