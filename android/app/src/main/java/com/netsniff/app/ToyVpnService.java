package com.netsniff.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ConnectivityManager;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;
import java.net.SocketAddress;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Iterator;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectableChannel;

public class ToyVpnService extends VpnService {
    private static final String TAG = "ToyVpnService";
    private static final int BUFFER_SIZE = 32767;
    private static final int MAX_PACKET_SIZE = 1500;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    
    // Action constants
    public static final String ACTION_CONNECT = "com.netsniff.app.START";
    public static final String ACTION_DISCONNECT = "com.netsniff.app.STOP";
    public static final String EXTRA_SERVER_ADDRESS = "serverAddress";
    public static final String EXTRA_SERVER_PORT = "serverPort";
    public static final String EXTRA_SHARED_SECRET = "sharedSecret";
    private static final int VPN_PREFIX_LENGTH = 32;
    private static final int ROUTE_PREFIX_LENGTH = 0;
    
    private static final int NOTIFICATION_ID = 1234;
    private static final String CHANNEL_ID = "NetSniffVpnChannel";
    
    private ParcelFileDescriptor vpnInterface = null;
    private ExecutorService executorService;
    private AtomicBoolean running = new AtomicBoolean(false);
    private Queue<ByteBuffer> deviceToNetworkQueue;
    private Queue<ByteBuffer> networkToDeviceQueue;
    private Network underlyingNetwork;
    private boolean isFirstPacket = true;
    
    // Connection tracking for TCP sessions
    private Map<String, DatagramChannel> udpConnections = new ConcurrentHashMap<>();
    private Map<String, SocketChannel> tcpConnections = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceToNetworkQueue = new ConcurrentLinkedQueue<>();
        networkToDeviceQueue = new ConcurrentLinkedQueue<>();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "NetSniff VPN Service",
                NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("NetSniff VPN Service Channel");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NetSniff VPN")
            .setContentText("Capturing network packets")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Check if intent is null
        if (intent == null) {
            Log.d(TAG, "Intent is null, keep running if already running");
            return START_STICKY;
        }
        
        // Check for disconnect action
        String action = intent.getAction();
        if (action != null && action.equals(ACTION_DISCONNECT)) {
            Log.d(TAG, "Received DISCONNECT action, stopping VPN service");
            stopVpn();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // Don't restart if already running
        if (running.get()) {
            Log.w(TAG, "VPN already running");
            return START_STICKY;
        }
        
        // Start as a foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification());

        deviceToNetworkQueue = new ConcurrentLinkedQueue<>();
        networkToDeviceQueue = new ConcurrentLinkedQueue<>();
        executorService = Executors.newFixedThreadPool(3);
        establishVpn();
        
        return START_STICKY;
    }

    private void establishVpn() {
        try {
            Builder builder = new Builder()
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                .addRoute(VPN_ROUTE, ROUTE_PREFIX_LENGTH)
                .setSession("NetSniff")
                .setMtu(MAX_PACKET_SIZE)
                .allowFamily(android.system.OsConstants.AF_INET)
                .allowFamily(android.system.OsConstants.AF_INET6);

            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");

            // Exclude our app from the VPN to avoid loops
            builder.addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN connection");
                stopForeground(true);
                stopSelf();
                return;
            }

            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            underlyingNetwork = cm.getActiveNetwork();
            setUnderlyingNetworks(new Network[]{underlyingNetwork});

            running.set(true);
            
            executorService.submit(new VPNRunnable());
            executorService.submit(new NetworkRunnable());
            
            Log.d(TAG, "VPN connection established successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            cleanup();
            stopForeground(true);
            stopSelf();
        }
    }

    private class VPNRunnable implements Runnable {
        @Override
        public void run() {
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            while (running.get()) {
                try {
                    packet.clear();
                    int length = in.read(packet.array());
                    if (length > 0) {
                        packet.limit(length);
                        ByteBuffer copy = ByteBuffer.allocate(length);
                        copy.put(packet.array(), 0, length);
                        copy.flip();
                        
                        // Process outgoing packets
                        processPacket(copy, "outgoing");
                        
                        deviceToNetworkQueue.offer(copy);
                    }
                    
                    // Check for incoming packets
                    ByteBuffer received = networkToDeviceQueue.poll();
                    if (received != null) {
                        out.write(received.array(), 0, received.limit());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "VPN thread error", e);
                    if (!running.get()) break;
                }
            }
        }
    }

    private class NetworkRunnable implements Runnable {
        @Override
        public void run() {
            try {
                // Main selector for all socket operations
                Selector selector = Selector.open();
                ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

                while (running.get()) {
                    // Handle outgoing packets from device
                    ByteBuffer toSend = deviceToNetworkQueue.poll();
                    if (toSend != null) {
                        handleOutgoingPacket(toSend, selector);
                    }
                    
                    // Check for incoming packets from network with a reasonable timeout
                    if (selector.select(50) > 0) {
                        Set<SelectionKey> keys = selector.selectedKeys();
                        Iterator<SelectionKey> keyIterator = keys.iterator();
                        
                        while (keyIterator.hasNext()) {
                            SelectionKey key = keyIterator.next();
                            keyIterator.remove();
                            
                            if (!key.isValid()) {
                                continue;
                            }
                            
                            if (key.isReadable()) {
                                packet.clear();
                                
                                // Get the associated channel
                                if (key.channel() instanceof DatagramChannel) {
                                    DatagramChannel channel = (DatagramChannel) key.channel();
                                    SocketAddress sourceAddress = channel.receive(packet);
                                    
                                    if (sourceAddress != null && packet.position() > 0) {
                                        packet.flip();
                                        int receivedLen = packet.limit();
                                        // Find connection info to build proper IP headers
                                        String connectionKey = null;
                                        InetSocketAddress srcAddress = (InetSocketAddress) sourceAddress;
                                        
                                        // Find the matching connection key from our map
                                        for (Map.Entry<String, DatagramChannel> entry : udpConnections.entrySet()) {
                                            if (entry.getValue().equals(channel)) {
                                                connectionKey = entry.getKey();
                                                break;
                                            }
                                        }
                                        
                                        if (connectionKey != null) {
                                            // Parse connection key to get original source/dest
                                            String[] parts = connectionKey.split("-");
                                            if (parts.length == 2) {
                                                // Create a properly formatted IP packet
                                                ByteBuffer ipPacket = createIPPacket(
                                                    parts[1].split(":")[0], // destination IP becomes source
                                                    parts[0].split(":")[0], // source IP becomes destination
                                                    Integer.parseInt(parts[1].split(":")[1]), // dest port becomes source
                                                    Integer.parseInt(parts[0].split(":")[1]), // source port becomes dest
                                                    17, // UDP protocol
                                                    packet,
                                                    receivedLen
                                                );
                                                
                                                processPacket(ipPacket, "incoming");
                                                networkToDeviceQueue.offer(ipPacket);
                                                Log.d(TAG, "Received UDP packet from network: " + receivedLen + " bytes");
                                            }
                                        } else {
                                            Log.w(TAG, "Received UDP data but couldn't find matching connection key");
                                        }
                                    }
                                } else if (key.channel() instanceof SocketChannel) {
                                    SocketChannel channel = (SocketChannel) key.channel();
                                    int bytesRead = channel.read(packet);
                                    
                                    if (bytesRead > 0) {
                                        packet.flip();
                                        
                                        // Find connection info to build proper IP headers
                                        String connectionKey = null;
                                        for (Map.Entry<String, SocketChannel> entry : tcpConnections.entrySet()) {
                                            if (entry.getValue().equals(channel)) {
                                                connectionKey = entry.getKey();
                                                break;
                                            }
                                        }
                                        
                                        if (connectionKey != null) {
                                            // Parse connection key to get original source/dest
                                            String[] parts = connectionKey.split("-");
                                            if (parts.length == 2) {
                                                // Create a properly formatted IP packet
                                                ByteBuffer ipPacket = createIPPacket(
                                                    parts[1].split(":")[0], // destination IP becomes source
                                                    parts[0].split(":")[0], // source IP becomes destination
                                                    Integer.parseInt(parts[1].split(":")[1]), // dest port becomes source
                                                    Integer.parseInt(parts[0].split(":")[1]), // source port becomes dest
                                                    6, // TCP protocol
                                                    packet,
                                                    bytesRead
                                                );
                                                
                                                processPacket(ipPacket, "incoming");
                                                networkToDeviceQueue.offer(ipPacket);
                                                Log.d(TAG, "Received TCP packet from network: " + bytesRead + " bytes");
                                            }
                                        } else {
                                            Log.w(TAG, "Received TCP data but couldn't find matching connection key");
                                        }
                                        
                                    } else if (bytesRead == -1) {
                                        // Connection closed
                                        channel.close();
                                        key.cancel();
                                        
                                        // Remove from connection tracking
                                        String connectionKey = getConnectionKey(channel.socket());
                                        if (connectionKey != null) {
                                            tcpConnections.remove(connectionKey);
                                            Log.d(TAG, "TCP connection closed: " + connectionKey);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Cleanup all connections when exiting
                closeAllConnections(selector);
                selector.close();
                
            } catch (Exception e) {
                Log.e(TAG, "Network thread error", e);
            }
        }
        
        /**
         * Create a proper IP packet with headers for sending back to the VPN interface
         */
        private ByteBuffer createIPPacket(String sourceIp, String destIp, int sourcePort, int destPort, 
                                        int protocol, ByteBuffer payload, int payloadSize) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(payloadSize + 40); // IP + TCP/UDP header
                
                // IP header (20 bytes minimum)
                buffer.put((byte) 0x45);                // Version 4, header length 5 words (20 bytes)
                buffer.put((byte) 0x00);                // DSCP/ECN
                buffer.putShort((short) (payloadSize + 40)); // Total length
                
                buffer.putShort((short) 0);            // Identification
                buffer.putShort((short) 0x4000);       // Flags and fragment offset (don't fragment)
                
                buffer.put((byte) 64);                 // TTL
                buffer.put((byte) protocol);           // Protocol (TCP=6, UDP=17)
                
                short checksum = 0;                    // Checksum placeholder
                buffer.putShort(checksum);             
                
                // Source IP
                String[] sourceParts = sourceIp.split("\\.");
                for (String part : sourceParts) {
                    buffer.put((byte) Integer.parseInt(part));
                }
                
                // Dest IP
                String[] destParts = destIp.split("\\.");
                for (String part : destParts) {
                    buffer.put((byte) Integer.parseInt(part));
                }
                
                // TCP/UDP header
                buffer.putShort((short) sourcePort);   // Source port
                buffer.putShort((short) destPort);     // Dest port
                
                if (protocol == 6) { // TCP
                    // Sequence number and other TCP fields
                    buffer.putInt(0);                  // Sequence number
                    buffer.putInt(0);                  // Acknowledgment number
                    buffer.putShort((short) 0x5000);   // Data offset, reserved, flags
                    buffer.putShort((short) 8192);     // Window size
                    buffer.putShort((short) 0);        // Checksum (compute later)
                    buffer.putShort((short) 0);        // Urgent pointer
                } else { // UDP
                    buffer.putShort((short) (8 + payloadSize)); // Length (header + data)
                    buffer.putShort((short) 0);        // Checksum (compute later)
                }
                
                // Add payload
                if (payload != null) {
                    buffer.put(payload.array(), 0, payloadSize);
                }
                
                buffer.flip();
                return buffer;
            } catch (Exception e) {
                Log.e(TAG, "Error creating IP packet", e);
                return null;
            }
        }
        
        
        private void closeAllConnections(Selector selector) throws IOException {
            Log.d(TAG, "Closing all network connections");
            
            // Close all UDP connections
            for (DatagramChannel channel : udpConnections.values()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing UDP connection", e);
                }
            }
            udpConnections.clear();
            
            // Close all TCP connections
            for (SocketChannel channel : tcpConnections.values()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing TCP connection", e);
                }
            }
            tcpConnections.clear();
        }
        
        private void handleOutgoingPacket(ByteBuffer packet, Selector selector) {
            try {
                packet.position(0);
                byte versionAndIHL = packet.get();
                int version = (versionAndIHL >> 4) & 0xF;
                int ihl = versionAndIHL & 0xF;
                
                if (version != 4) {
                    // Only support IPv4 for now
                    return;
                }
                
                // Read packet info
                packet.position(9);
                int protocol = packet.get() & 0xFF;
                
                packet.position(12);
                byte[] sourceAddr = new byte[4];
                byte[] destAddr = new byte[4];
                packet.get(sourceAddr);
                packet.get(destAddr);
                
                int headerLength = ihl * 4;
                packet.position(headerLength);
                
                // Extract ports for TCP/UDP
                int sourcePort = 0;
                int destPort = 0;
                
                if (protocol == 6 || protocol == 17) { // TCP or UDP
                    sourcePort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                    destPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                }
                
                // Create destination address
                InetSocketAddress destAddress = new InetSocketAddress(
                    InetAddress.getByAddress(destAddr), destPort);
                
                String connectionKey = ipToString(sourceAddr) + ":" + sourcePort + "-" +
                                       ipToString(destAddr) + ":" + destPort;
                
                // Reset position to beginning for forwarding
                packet.position(0);
                int packetLength = packet.limit();
                
                if (protocol == 17) { // UDP
                    // Get or create UDP channel
                    DatagramChannel channel = udpConnections.get(connectionKey);
                    if (channel == null) {
                        channel = DatagramChannel.open();
                        channel.configureBlocking(false);
                        channel.register(selector, SelectionKey.OP_READ);
                        
                        // Protect socket to prevent loopback
                        protect(channel.socket());
                        
                        udpConnections.put(connectionKey, channel);
                        Log.d(TAG, "Created new UDP connection: " + connectionKey);
                    }
                    
                    // Forward packet
                    int sent = channel.send(packet, destAddress);
                    Log.d(TAG, "Sent UDP packet: " + sent + " bytes to " + destAddress);
                    
                } else if (protocol == 6) { // TCP
                    handleTCPPacket(packet, connectionKey, destAddress, selector, packetLength);
                } else {
                    // Other protocols not supported yet
                    Log.d(TAG, "Unsupported protocol: " + protocol);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error handling outgoing packet", e);
            }
        }
        
        private void handleTCPPacket(ByteBuffer packet, String connectionKey, 
                                   InetSocketAddress destAddress, Selector selector, int packetLength) {
            try {
                // Check TCP flags to determine packet type
                packet.position(13 + packet.get(0) % 16 * 4); // Position at TCP header flags
                byte flags = packet.get();
                boolean isSYN = (flags & 0x02) != 0;
                boolean isFIN = (flags & 0x01) != 0;
                boolean isRST = (flags & 0x04) != 0;
                
                // Reset to beginning for forwarding
                packet.position(0);
                
                SocketChannel channel = tcpConnections.get(connectionKey);
                
                if (isSYN && channel == null) {
                    // New connection
                    channel = SocketChannel.open();
                    channel.configureBlocking(false);
                    channel.register(selector, SelectionKey.OP_READ);
                    
                    // Protect socket to prevent loopback
                    protect(channel.socket());
                    
                    // Start connection (non-blocking)
                    channel.connect(destAddress);
                    
                    tcpConnections.put(connectionKey, channel);
                    Log.d(TAG, "Started new TCP connection: " + connectionKey);
                    
                    // Wait for connection to complete
                    long timeout = System.currentTimeMillis() + 5000; // 5 second timeout
                    while (!channel.finishConnect()) {
                        if (System.currentTimeMillis() > timeout) {
                            Log.e(TAG, "TCP connection timeout: " + connectionKey);
                            channel.close();
                            tcpConnections.remove(connectionKey);
                            return;
                        }
                        Thread.sleep(10);
                    }
                    
                    Log.d(TAG, "TCP connection established: " + connectionKey);
                    
                } else if ((isFIN || isRST) && channel != null) {
                    // Connection closing
                    // Forward the packet first
                    if (channel.isConnected()) {
                        ByteBuffer packetData = ByteBuffer.allocate(packetLength);
                        packetData.put(packet.array(), 0, packetLength);
                        packetData.flip();
                        channel.write(packetData);
                    }
                    
                    // Then close our side
                    channel.close();
                    tcpConnections.remove(connectionKey);
                    Log.d(TAG, "TCP connection closed: " + connectionKey);
                    return;
                    
                } else if (channel != null && channel.isConnected()) {
                    // Existing connection - forward the data
                    ByteBuffer packetData = ByteBuffer.allocate(packetLength);
                    packetData.put(packet.array(), 0, packetLength);
                    packetData.flip();
                    channel.write(packetData);
                } else if (channel == null) {
                    Log.d(TAG, "No existing TCP connection for: " + connectionKey);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error handling TCP packet", e);
            }
        }
        
        private String getConnectionKey(Socket socket) {
            for (Map.Entry<String, SocketChannel> entry : tcpConnections.entrySet()) {
                if (entry.getValue().socket().equals(socket)) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }

    private void processPacket(ByteBuffer packet, String direction) {
        try {
            packet.position(0);
            byte versionAndIHL = packet.get();
            int version = (versionAndIHL >> 4) & 0xF;
            int ihl = versionAndIHL & 0xF;
            
            if (version != 4) {
                return;
            }

            packet.position(2);
            int totalLength = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);

            packet.position(9);
            int protocol = packet.get() & 0xFF;

            packet.position(12);
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            packet.get(sourceAddr);
            packet.get(destAddr);

            int headerLength = ihl * 4;
            String sourcePort = "";
            String destPort = "";
            if (protocol == 6 || protocol == 17) {
                packet.position(headerLength);
                sourcePort = String.valueOf(((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF));
                destPort = String.valueOf(((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF));
            }

            JSObject packetInfo = new JSObject();
            packetInfo.put("source", ipToString(sourceAddr) + (sourcePort.isEmpty() ? "" : ":" + sourcePort));
            packetInfo.put("destination", ipToString(destAddr) + (destPort.isEmpty() ? "" : ":" + destPort));
            packetInfo.put("protocol", getProtocolName(protocol));
            packetInfo.put("direction", direction);
            packetInfo.put("size", totalLength);

            StringBuilder payload = new StringBuilder();
            int payloadStart = headerLength;
            int payloadLength = Math.min(totalLength - payloadStart, 64);
            packet.position(payloadStart);
            for (int i = 0; i < payloadLength && packet.hasRemaining(); i++) {
                payload.append(String.format("%02X ", packet.get()));
                if ((i + 1) % 16 == 0) payload.append("\n");
            }
            packetInfo.put("payload", payload.toString().trim());

            if (isFirstPacket) {
                Log.d(TAG, "First packet captured: " + packetInfo.toString());
                isFirstPacket = false;
            }

            ToyVpnPlugin.notifyPacketCaptured(packetInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error processing packet", e);
        }
    }

    private String ipToString(byte[] addr) {
        return String.format("%d.%d.%d.%d", addr[0] & 0xFF, addr[1] & 0xFF, addr[2] & 0xFF, addr[3] & 0xFF);
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

    /**
     * Stops the VPN connection
     */
    public void stopVpn() {
        Log.d(TAG, "Stopping VPN connection");
        cleanup();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "VPN service being destroyed");
        cleanup();
        stopForeground(true);
        super.onDestroy();
    }

    private void cleanup() {
        Log.d(TAG, "Performing safe VPN cleanup");
        
        // Mark as not running to ensure no new packets are processed
        running.set(false);
        
        // Close the VPN interface properly
        if (vpnInterface != null) {
            try {
                Log.d(TAG, "Closing VPN interface");
                vpnInterface.close();
                Log.d(TAG, "VPN interface closed successfully");
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            } finally {
                vpnInterface = null;
            }
        }
        
        // Safely shut down the executor service
        if (executorService != null && !executorService.isTerminated()) {
            try {
                Log.d(TAG, "Shutting down executor service");
                // First try gentle shutdown
                executorService.shutdown();
                
                // Wait for tasks to complete with reasonable timeout
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Executor not responding to gentle shutdown, using shutdownNow");
                    List<Runnable> pendingTasks = executorService.shutdownNow();
                    Log.d(TAG, "Cancelled " + pendingTasks.size() + " pending executor tasks");
                    
                    // Wait again with longer timeout
                    if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        Log.e(TAG, "Executor service did not terminate");
                    }
                }
            } catch (InterruptedException ie) {
                Log.e(TAG, "Interrupted while waiting for executor shutdown", ie);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Error during executor service shutdown", e);
            } finally {
                executorService = null;
            }
        }
        
        // Clear queues
        if (deviceToNetworkQueue != null) {
            try {
                deviceToNetworkQueue.clear();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing deviceToNetworkQueue", e);
            }
            deviceToNetworkQueue = null;
        }
        
        if (networkToDeviceQueue != null) {
            try {
                networkToDeviceQueue.clear();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing networkToDeviceQueue", e);
            }
            networkToDeviceQueue = null;
        }
        
        // Notify JavaScript layer that VPN is stopped
        try {
            if (ToyVpnPlugin.instance != null) {
                ToyVpnPlugin.notifyVpnStopped();
                Log.d(TAG, "Notified JS layer that VPN is stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error notifying JS layer about VPN stop", e);
        }
        
        // Release network
        underlyingNetwork = null;
        
        // Reset state
        isFirstPacket = true;
        
        // Force garbage collection to clean up resources
        try {
            System.gc();
            Log.d(TAG, "EXTREME VPN TERMINATION: Requested garbage collection");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting garbage collection", e);
        }
        
        Log.d(TAG, "EXTREME VPN TERMINATION: VPN cleanup completed");
        
        // Forcefully stop the service immediately after cleanup
        stopSelf();
        
        // LAST RESORT: Detect if we're still active after cleanup and force kill
        try {
            // Staggered checks to ensure termination
            // First check quickly
            new android.os.Handler().postDelayed(() -> {
                if (isProcessStillActive()) {
                    Log.w(TAG, "EXTREME VPN TERMINATION: VPN still running after 200ms, trying additional cleanup");
                    // Try one more aggressive interruption of ALL threads
                    Thread.getAllStackTraces().keySet().forEach(thread -> {
                        if (thread != Thread.currentThread()) {
                            try {
                                thread.interrupt();
                            } catch (Exception ignored) {}
                        }
                    });
                    
                    // Second check with a more drastic action
                    new android.os.Handler().postDelayed(() -> {
                        if (isProcessStillActive()) {
                            Log.e(TAG, "EXTREME VPN TERMINATION: VPN STILL RUNNING AFTER MULTIPLE CLEANUPS - FORCE KILLING PROCESS!");
                            try {
                                // Signal the plugin about imminent termination
                                if (ToyVpnPlugin.instance != null) {
                                    ToyVpnPlugin.notifyVpnStopped();
                                }
                            } catch (Exception ignored) {}
                            
                            // Absolutely final resort - kill the entire process
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    }, 300);
                }
            }, 200);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up process kill safety mechanism", e);
        }
    }
    
    private boolean isProcessStillActive() {
        // Check if we're still receiving packets or if threads are still active
        // This is a safety mechanism to detect if cleanup failed
        try {
            // First check if executor is still active
            if (executorService != null && !executorService.isTerminated()) {
                Log.d(TAG, "EXTREME VPN TERMINATION: Executor service still active");
                return true;
            }
            
            // Check if VPN interface is still active
            if (vpnInterface != null) {
                Log.d(TAG, "EXTREME VPN TERMINATION: VPN interface still active");
                return true;
            }
            
            // More thorough thread checking - look for ANY thread related to network or VPN
            Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
            for (Thread thread : threadSet) {
                // Only check other threads, not the current one
                if (thread != Thread.currentThread()) {
                    // More extensive name check including internal IDs
                    String threadName = thread.getName().toLowerCase();
                    if (threadName.contains("vpn") || 
                        threadName.contains("toy") || 
                        threadName.contains("network") || 
                        threadName.contains("packet") || 
                        threadName.contains("pool") || 
                        threadName.contains("executor") || 
                        threadName.contains("interface") || 
                        threadName.contains("tunnel")) {
                        
                        Log.d(TAG, "EXTREME VPN TERMINATION: Found active related thread: " + thread.getName());
                        return true;
                    }
                    
                    // Check thread status for potentially blocked states
                    if (thread.getState() == Thread.State.BLOCKED || 
                        thread.getState() == Thread.State.WAITING || 
                        thread.getState() == Thread.State.TIMED_WAITING) {
                        
                        // Get stack trace to check what this thread is doing
                        StackTraceElement[] stack = thread.getStackTrace();
                        for (StackTraceElement element : stack) {
                            // Check if the thread is blocked in our VPN code
                            String className = element.getClassName();
                            if (className.contains("netsniff") || 
                                className.contains("ToyVpn") || 
                                className.contains("vpn")) {
                                
                                Log.d(TAG, "EXTREME VPN TERMINATION: Found thread blocked in VPN code: " + 
                                      thread.getName() + " at " + element);
                                return true;
                            }
                        }
                    }
                }
            }
            
            // Check if running flag is still true (shouldn't be at this point)
            if (running.get()) {
                Log.d(TAG, "EXTREME VPN TERMINATION: Running flag is still true");
                return true;
            }
            
            Log.d(TAG, "EXTREME VPN TERMINATION: No active VPN processes found - all clean");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking if process is still active", e);
            // Default to false in case of error, to prevent infinite loops
            return false;
        }
    }
}
