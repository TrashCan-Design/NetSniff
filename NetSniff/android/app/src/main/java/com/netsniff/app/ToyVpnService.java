package com.netsniff.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.net.Network;
import android.net.ConnectivityManager;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ToyVpnService extends VpnService {
    private static final String TAG = "ToyVpnService";
    private static final int MAX_PACKET_SIZE = 32767;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final int VPN_PREFIX_LENGTH = 32;
    private static final int ROUTE_PREFIX_LENGTH = 0;
    
    private static final int NOTIFICATION_ID = 1234;
    private static final String CHANNEL_ID = "NetSniffVpnChannel";
    
    // Action constants
    public static final String ACTION_CONNECT = "com.netsniff.app.START";
    public static final String ACTION_DISCONNECT = "com.netsniff.app.STOP";
    
    // Packet rate limiting
    private static final int MAX_PACKETS_PER_SECOND = 100;
    private static final long PACKET_EMIT_INTERVAL_MS = 1000 / MAX_PACKETS_PER_SECOND;
    
    private ParcelFileDescriptor vpnInterface = null;
    private ExecutorService executorService;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    // Efficient packet queues with size limits
    private BlockingQueue<ByteBuffer> outgoingQueue;
    private BlockingQueue<ByteBuffer> incomingQueue;
    
    private Thread vpnThread;
    private Thread forwardThread;
    private Thread notificationThread;
    
    private DatagramChannel tunnel;
    private long lastPacketEmitTime = 0;
    private PackageManager packageManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        packageManager = getPackageManager();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "NetSniff VPN Service",
                NotificationManager.IMPORTANCE_LOW); // LOW to avoid sound/vibration
            channel.setDescription("NetSniff VPN Service Channel");
            channel.setShowBadge(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        // Add stop action
        Intent stopIntent = new Intent(this, ToyVpnService.class);
        stopIntent.setAction(ACTION_DISCONNECT);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NetSniff VPN Active")
            .setContentText("Capturing network packets")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
            
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            Log.d(TAG, "Received graceful stop request");
            stopVpnGracefully();
            return START_NOT_STICKY;
        }
        
        if (running.get()) {
            Log.w(TAG, "VPN already running");
            return START_STICKY;
        }
        
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize queues with capacity limits
        outgoingQueue = new LinkedBlockingQueue<>(1000);
        incomingQueue = new LinkedBlockingQueue<>(1000);
        
        // Use fixed thread pool for better resource management
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
                .setBlocking(false); // Non-blocking mode for better performance

            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");

            // Exclude our app to avoid loops
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Failed to exclude app", e);
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }

            // Set underlying network
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    setUnderlyingNetworks(new Network[]{activeNetwork});
                }
            }

            running.set(true);
            
            // Start packet processing threads
            vpnThread = new Thread(new VPNInputRunnable(), "VPN-Input");
            forwardThread = new Thread(new PacketForwardRunnable(), "VPN-Forward");
            notificationThread = new Thread(new PacketNotificationRunnable(), "VPN-Notify");
            
            vpnThread.start();
            forwardThread.start();
            notificationThread.start();
            
            Log.d(TAG, "VPN established successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            stopVpnGracefully();
        }
    }

    /**
     * Reads packets from VPN interface (device to network)
     */
    private class VPNInputRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "VPN input thread started");
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        packet.clear();
                        int length = in.read(packet.array());
                        
                        if (length > 0) {
                            packet.limit(length);
                            
                            // Create a copy for queue
                            ByteBuffer copy = ByteBuffer.allocate(length);
                            copy.put(packet.array(), 0, length);
                            copy.flip();
                            
                            // Try to add to queue, drop if full
                            if (!outgoingQueue.offer(copy)) {
                                Log.w(TAG, "Outgoing queue full, dropping packet");
                            }
                        } else if (length < 0) {
                            Log.w(TAG, "VPN input stream closed");
                            break;
                        }
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Error reading from VPN", e);
                        }
                        break;
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {}
                Log.d(TAG, "VPN input thread stopped");
            }
        }
    }

    /**
     * Forwards packets to their actual destinations and captures responses
     */
    private class PacketForwardRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Packet forward thread started");
            
            try {
                // Create tunnel for forwarding packets
                tunnel = DatagramChannel.open();
                tunnel.configureBlocking(false);
                protect(tunnel.socket()); // Protect from VPN to avoid loops
                
                ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
                
                while (running.get() && !Thread.interrupted()) {
                    try {
                        // Forward outgoing packets
                        ByteBuffer outgoing = outgoingQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (outgoing != null) {
                            forwardPacket(outgoing);
                        }
                        
                        // Check for incoming responses
                        buffer.clear();
                        InetSocketAddress sender = (InetSocketAddress) tunnel.receive(buffer);
                        if (sender != null) {
                            buffer.flip();
                            
                            // Create copy for queue
                            ByteBuffer incoming = ByteBuffer.allocate(buffer.remaining());
                            incoming.put(buffer);
                            incoming.flip();
                            
                            if (!incomingQueue.offer(incoming)) {
                                Log.w(TAG, "Incoming queue full, dropping packet");
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Error forwarding packet", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to create tunnel", e);
            } finally {
                if (tunnel != null) {
                    try {
                        tunnel.close();
                    } catch (IOException ignored) {}
                }
                Log.d(TAG, "Packet forward thread stopped");
            }
        }
        
        private void forwardPacket(ByteBuffer packet) throws IOException {
            // Extract destination IP from packet
            packet.position(16); // Destination IP offset
            byte[] destIp = new byte[4];
            packet.get(destIp);
            
            InetAddress destAddress = InetAddress.getByAddress(destIp);
            
            // Forward packet to actual destination
            packet.position(0);
            tunnel.send(packet, new InetSocketAddress(destAddress, 53)); // Use DNS port as default
        }
    }

    /**
     * Processes packets and notifies the app (with rate limiting)
     */
    private class PacketNotificationRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Packet notification thread started");
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        // Process outgoing packets
                        ByteBuffer outgoing = outgoingQueue.peek();
                        if (outgoing != null) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastPacketEmitTime >= PACKET_EMIT_INTERVAL_MS) {
                                outgoingQueue.poll(); // Remove from queue
                                processAndNotify(outgoing, "outgoing");
                                lastPacketEmitTime = currentTime;
                            }
                        }
                        
                        // Write incoming packets back to VPN interface
                        ByteBuffer incoming = incomingQueue.poll(50, TimeUnit.MILLISECONDS);
                        if (incoming != null) {
                            processAndNotify(incoming, "incoming");
                            out.write(incoming.array(), 0, incoming.limit());
                            out.flush();
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Error in notification thread", e);
                        }
                    }
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {}
                Log.d(TAG, "Packet notification thread stopped");
            }
        }
    }

    private void processAndNotify(ByteBuffer packet, String direction) {
        try {
            packet.position(0);
            
            // Parse IP header
            byte versionAndIHL = packet.get();
            int version = (versionAndIHL >> 4) & 0xF;
            if (version != 4) return; // Only IPv4 for now
            
            int ihl = (versionAndIHL & 0xF) * 4;
            
            packet.position(2);
            int totalLength = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
            
            packet.position(9);
            int protocol = packet.get() & 0xFF;
            
            packet.position(12);
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            packet.get(sourceAddr);
            packet.get(destAddr);
            
            // Parse ports for TCP/UDP
            String sourcePort = "";
            String destPort = "";
            int uid = -1;
            
            if (protocol == 6 || protocol == 17) { // TCP or UDP
                packet.position(ihl);
                int srcPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                int dstPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                sourcePort = String.valueOf(srcPort);
                destPort = String.valueOf(dstPort);
                
                // Get UID for app attribution
                if (direction.equals("outgoing")) {
                    uid = getUidForConnection(protocol, srcPort);
                }
            }
            
            // Get app name
            String appName = "Unknown";
            String packageName = "unknown";
            if (uid >= 10000) { // User apps start at UID 10000
                String[] packages = packageManager.getPackagesForUid(uid);
                if (packages != null && packages.length > 0) {
                    packageName = packages[0];
                    try {
                        ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                        appName = packageManager.getApplicationLabel(appInfo).toString();
                    } catch (PackageManager.NameNotFoundException e) {
                        appName = packageName;
                    }
                }
            } else if (uid == 1000) {
                appName = "Android System";
                packageName = "android";
            }
            
            // Build packet info
            JSObject packetInfo = new JSObject();
            packetInfo.put("source", ipToString(sourceAddr) + (sourcePort.isEmpty() ? "" : ":" + sourcePort));
            packetInfo.put("destination", ipToString(destAddr) + (destPort.isEmpty() ? "" : ":" + destPort));
            packetInfo.put("protocol", getProtocolName(protocol));
            packetInfo.put("direction", direction);
            packetInfo.put("size", totalLength);
            packetInfo.put("appName", appName);
            packetInfo.put("packageName", packageName);
            
            // Extract payload (limited to first 64 bytes)
            StringBuilder payload = new StringBuilder();
            int payloadStart = ihl;
            int payloadLength = Math.min(totalLength - payloadStart, 64);
            packet.position(payloadStart);
            for (int i = 0; i < payloadLength && packet.hasRemaining(); i++) {
                payload.append(String.format("%02X ", packet.get()));
                if ((i + 1) % 16 == 0) payload.append("\n");
            }
            packetInfo.put("payload", payload.toString().trim());
            
            // Notify plugin
            ToyVpnPlugin.notifyPacketCaptured(packetInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing packet", e);
        }
    }
    
    private int getUidForConnection(int protocol, int sourcePort) {
        // Note: Getting UID requires parsing /proc/net/tcp or /proc/net/udp
        // This is a simplified version - full implementation would parse these files
        return android.os.Process.myUid(); // Fallback
    }

    private String ipToString(byte[] addr) {
        return String.format("%d.%d.%d.%d", 
            addr[0] & 0xFF, addr[1] & 0xFF, addr[2] & 0xFF, addr[3] & 0xFF);
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

    public void stopVpnGracefully() {
        if (shuttingDown.getAndSet(true)) {
            Log.d(TAG, "Already shutting down");
            return;
        }
        
        Log.d(TAG, "Starting graceful VPN shutdown");
        running.set(false);
        
        // Interrupt and wait for threads
        new Thread(() -> {
            try {
                // Interrupt threads
                if (vpnThread != null) vpnThread.interrupt();
                if (forwardThread != null) forwardThread.interrupt();
                if (notificationThread != null) notificationThread.interrupt();
                
                // Wait for threads to finish
                if (vpnThread != null) vpnThread.join(1000);
                if (forwardThread != null) forwardThread.join(1000);
                if (notificationThread != null) notificationThread.join(1000);
                
                // Close resources
                if (tunnel != null) {
                    try { tunnel.close(); } catch (IOException ignored) {}
                }
                
                if (vpnInterface != null) {
                    try { vpnInterface.close(); } catch (IOException ignored) {}
                    vpnInterface = null;
                }
                
                // Shutdown executor
                if (executorService != null) {
                    executorService.shutdown();
                    executorService.awaitTermination(1, TimeUnit.SECONDS);
                }
                
                // Clear queues
                if (outgoingQueue != null) outgoingQueue.clear();
                if (incomingQueue != null) incomingQueue.clear();
                
                // Notify plugin
                ToyVpnPlugin.notifyVpnStopped();
                
                Log.d(TAG, "Graceful shutdown complete");
                
            } catch (Exception e) {
                Log.e(TAG, "Error during graceful shutdown", e);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }, "VPN-Shutdown").start();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        if (!shuttingDown.get()) {
            stopVpnGracefully();
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        Log.d(TAG, "VPN permission revoked");
        stopVpnGracefully();
        super.onRevoke();
    }
}