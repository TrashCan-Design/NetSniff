package com.netsniff.app;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ToyVpnService extends VpnService {
    private static final String TAG = "ToyVpnService";
    
    public static final String EXTRA_SERVER_ADDRESS = "serverAddress";
    public static final String EXTRA_SERVER_PORT = "serverPort";
    public static final String EXTRA_SHARED_SECRET = "sharedSecret";
    
    private String serverAddress;
    private int serverPort;
    private String sharedSecret;
    
    private ParcelFileDescriptor vpnInterface = null;
    private ExecutorService executorService;
    private AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS);
            serverPort = Integer.parseInt(intent.getStringExtra(EXTRA_SERVER_PORT));
            sharedSecret = intent.getStringExtra(EXTRA_SHARED_SECRET);
        }
        
        // Default values if not provided
        if (serverAddress == null) serverAddress = "127.0.0.1";
        if (serverPort == 0) serverPort = 8000;
        if (sharedSecret == null) sharedSecret = "shared_secret";
        
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::establishVpn);
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        executorService.shutdownNow();
        
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                vpnInterface = null;
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
        }
        
        super.onDestroy();
    }

    private void establishVpn() {
        try {
            // Configure VPN connection
            Builder builder = new Builder()
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .setSession("NetSniff VPN")
                    .setMtu(1500);
            
            // Create VPN interface
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN connection");
                return;
            }
            
            running.set(true);
            capturePackets();
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            running.set(false);
        }
    }

    private void capturePackets() {
        try {
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            
            ByteBuffer packet = ByteBuffer.allocate(32767);
            
            while (running.get() && !Thread.interrupted()) {
                // Read outgoing packet
                int length = in.read(packet.array());
                if (length > 0) {
                    // Process outgoing packet
                    packet.limit(length);
                    processPacket(packet, "outgoing");
                    
                    // Forward the packet (in a real VPN implementation)
                    // For this app, we're just intercepting and displaying packets
                    out.write(packet.array(), 0, length);
                    
                    packet.clear();
                }
                
                // Sleep to prevent high CPU usage
                Thread.sleep(10);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in packet capture loop", e);
        } finally {
            running.set(false);
        }
    }

    private void processPacket(ByteBuffer packet, String direction) {
        try {
            // Packet is a ByteBuffer, so we need to parse it
            // For simplicity, we'll just extract basic IPv4 header data
            
            // Make sure we don't change the position of the original buffer
            packet.position(0);
            
            // IPv4 header parsing
            byte versionAndIHL = packet.get(); // Version and IHL
            int version = (versionAndIHL >> 4) & 0xF;
            int ihl = versionAndIHL & 0xF;
            
            if (version != 4) {
                // Not an IPv4 packet, ignore for now
                return;
            }
            
            // Skip to source and destination addresses
            packet.position(12);
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            packet.get(sourceAddr);
            packet.get(destAddr);
            
            String sourceIp = ipToString(sourceAddr);
            String destIp = ipToString(destAddr);
            
            // Determine protocol
            packet.position(9);
            int protocol = packet.get() & 0xFF;
            String protocolName = getProtocolName(protocol);
            
            // Get header length for finding payload
            int headerLength = ihl * 4;
            
            // Extract source and destination ports for TCP/UDP
            String sourcePort = "";
            String destPort = "";
            if (protocol == 6 || protocol == 17) { // TCP or UDP
                packet.position(headerLength);
                sourcePort = String.valueOf(((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF));
                destPort = String.valueOf(((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF));
            }
            
            // Create info about this packet
            String info = "";
            if (!sourcePort.isEmpty() && !destPort.isEmpty()) {
                info = sourceIp + ":" + sourcePort + " → " + destIp + ":" + destPort;
            } else {
                info = sourceIp + " → " + destIp;
            }
            
            // Reset buffer position
            packet.position(0);
            
            // Data part (simplified, in a real implementation we'd properly parse this)
            StringBuilder dataBuilder = new StringBuilder();
            for (int i = 0; i < Math.min(packet.limit(), 64); i++) {
                dataBuilder.append(String.format("%02X ", packet.get(i) & 0xFF));
                if ((i + 1) % 16 == 0) dataBuilder.append("\n");
            }
            
            // Create packet data to send to the app
            JSObject packetData = new JSObject();
            packetData.put("source", sourceIp + (sourcePort.isEmpty() ? "" : ":" + sourcePort));
            packetData.put("destination", destIp + (destPort.isEmpty() ? "" : ":" + destPort));
            packetData.put("protocol", protocolName);
            packetData.put("info", info);
            packetData.put("data", dataBuilder.toString());
            packetData.put("direction", direction);
            
            // Send to the React app via the plugin event emitter
            ToyVpnPlugin.notifyPacketCaptured(packetData);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing packet", e);
        }
    }

    private String ipToString(byte[] addr) {
        return (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + 
               (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
    }

    private String getProtocolName(int protocol) {
        switch (protocol) {
            case 1: return "ICMP";
            case 6: return "TCP";
            case 17: return "UDP";
            case 50: return "ESP";
            case 89: return "OSPF";
            default: return "IP(" + protocol + ")";
        }
    }
}
