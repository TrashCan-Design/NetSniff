package com.netsniff.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.getcapacitor.JSObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    
    public static final String ACTION_CONNECT = "com.netsniff.app.START";
    public static final String ACTION_DISCONNECT = "com.netsniff.app.STOP";
    
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.google.android.googlequicksearchbox",
        "com.google.android.youtube"
    ));
    
    private ParcelFileDescriptor vpnInterface = null;
    private ExecutorService executorService;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    private Thread vpnThread;
    private Thread tcpForwardThread;
    private Thread udpForwardThread;
    private Thread icmpForwardThread;
    private Thread notificationThread;
    private Thread usageStatsThread;
    
    private Selector udpSelector;
    private Selector tcpSelector;
    
    private ConcurrentHashMap<String, TcpConnection> tcpConnections;
    private ConcurrentHashMap<String, UdpConnection> udpConnections;
    private Set<Integer> allowedUids;
    
    private BlockingQueue<Packet> outgoingPackets;
    private BlockingQueue<Packet> incomingPackets;
    
    private AtomicLong packetCounter = new AtomicLong(0);
    private PackageManager packageManager;
    private UsageStatsManager usageStatsManager;
    
    // UID to Package/App name cache (from reference code)
    private Map<Integer, List<String>> uidToPackagesMap;
    private Map<Integer, String> uidToAppNameMap;
    private Map<String, Long> recentForegroundApps; // package -> last seen timestamp
    private final Object uidCacheLock = new Object();

    private static class Packet {
        ByteBuffer data;
        String key;
        boolean isIncoming;
        long packetNumber;
        int protocol;
        
        Packet(ByteBuffer data, String key, boolean isIncoming, long packetNumber, int protocol) {
            this.data = data;
            this.key = key;
            this.isIncoming = isIncoming;
            this.packetNumber = packetNumber;
            this.protocol = protocol;
        }
    }
    
    private static class TcpConnection {
        SocketChannel channel;
        String sourceIp;
        int sourcePort;
        String destIp;
        int destPort;
        long lastActivity;
        ByteBuffer readBuffer;
        int uid;
        
        TcpConnection(String sourceIp, int sourcePort, String destIp, int destPort, int uid) {
            this.sourceIp = sourceIp;
            this.sourcePort = sourcePort;
            this.destIp = destIp;
            this.destPort = destPort;
            this.uid = uid;
            this.lastActivity = System.currentTimeMillis();
            this.readBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
        }
    }
    
    private static class UdpConnection {
        DatagramChannel channel;
        String sourceIp;
        int sourcePort;
        String destIp;
        int destPort;
        long lastActivity;
        int uid;
        
        UdpConnection(String sourceIp, int sourcePort, String destIp, int destPort, int uid) {
            this.sourceIp = sourceIp;
            this.sourcePort = sourcePort;
            this.destIp = destIp;
            this.destPort = destPort;
            this.uid = uid;
            this.lastActivity = System.currentTimeMillis();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        packageManager = getPackageManager();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        
        tcpConnections = new ConcurrentHashMap<>();
        udpConnections = new ConcurrentHashMap<>();
        allowedUids = new HashSet<>();
        uidToPackagesMap = new HashMap<>();
        uidToAppNameMap = new HashMap<>();
        recentForegroundApps = new HashMap<>();
        
        buildUidCache();
        
        for (String pkg : ALLOWED_PACKAGES) {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(pkg, 0);
                allowedUids.add(appInfo.uid);
                Log.d(TAG, "Added allowed app: " + pkg + " (UID: " + appInfo.uid + ")");
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Package not found: " + pkg);
            }
        }
    }
    
    /**
     * Build UID cache from installed packages (reference code logic)
     */
    private void buildUidCache() {
        new Thread(() -> {
            List<ApplicationInfo> apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            Map<Integer, List<String>> tempUidMap = new HashMap<>();
            Map<Integer, String> tempAppNameMap = new HashMap<>();
            
            for (ApplicationInfo ai : apps) {
                int uid = ai.uid;
                String pkg = ai.packageName;
                
                List<String> list = tempUidMap.computeIfAbsent(uid, k -> new ArrayList<>());
                list.add(pkg);
                
                if (!tempAppNameMap.containsKey(uid)) {
                    try {
                        String appName = packageManager.getApplicationLabel(ai).toString();
                        tempAppNameMap.put(uid, appName);
                    } catch (Exception e) {
                        tempAppNameMap.put(uid, pkg);
                    }
                }
            }
            
            synchronized (uidCacheLock) {
                uidToPackagesMap = tempUidMap;
                uidToAppNameMap = tempAppNameMap;
            }
            
            Log.d(TAG, "UID cache built: " + uidToPackagesMap.size() + " unique UIDs");
        }, "UID-Cache-Builder").start();
    }
    
    /**
     * Get UID for package (reference code logic)
     */
    private int[] getUidsForPackage(String packageName) {
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName, 0);
            return new int[]{ai.uid};
        } catch (PackageManager.NameNotFoundException ignored) {}
        return null;
    }
    
    /**
     * Get package name for UID from cache
     */
    private String getPackageNameForUid(int uid) {
        synchronized (uidCacheLock) {
            List<String> packages = uidToPackagesMap.get(uid);
            if (packages != null && !packages.isEmpty()) {
                return packages.get(0);
            }
        }
        
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        
        return "unknown";
    }
    
    /**
     * Get app name for UID from cache
     */
    private String getAppNameForUid(int uid) {
        synchronized (uidCacheLock) {
            String appName = uidToAppNameMap.get(uid);
            if (appName != null) {
                return appName;
            }
        }
        
        if (uid == 1000) {
            return "Android System";
        }
        
        String packageName = getPackageNameForUid(uid);
        if (!packageName.equals("unknown")) {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                return packageManager.getApplicationLabel(appInfo).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return packageName;
            }
        }
        
        return "Unknown";
    }
    
    /**
     * Get most likely UID for current network activity using UsageStats
     * (Reference code implementation)
     */
    private int getMostLikelyActiveUid() {
        synchronized (uidCacheLock) {
            String mostRecentPackage = null;
            long mostRecentTime = 0;
            
            for (Map.Entry<String, Long> entry : recentForegroundApps.entrySet()) {
                if (entry.getValue() > mostRecentTime) {
                    mostRecentTime = entry.getValue();
                    mostRecentPackage = entry.getKey();
                }
            }
            
            if (mostRecentPackage != null) {
                int[] uids = getUidsForPackage(mostRecentPackage);
                if (uids != null && uids.length > 0) {
                    return uids[0];
                }
            }
        }
        return -1;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "NetSniff VPN Service",
                NotificationManager.IMPORTANCE_LOW);
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
        
        Intent stopIntent = new Intent(this, ToyVpnService.class);
        stopIntent.setAction(ACTION_DISCONNECT);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NetSniff Per-App VPN")
            .setContentText("Monitoring: Chrome, Edge, Google, YouTube")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
            
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            Log.d(TAG, "Received stop request");
            stopVpnGracefully();
            return START_NOT_STICKY;
        }
        
        if (running.get()) {
            Log.w(TAG, "VPN already running");
            return START_STICKY;
        }
        
        startForeground(NOTIFICATION_ID, createNotification());
        
        outgoingPackets = new LinkedBlockingQueue<>(2000);
        incomingPackets = new LinkedBlockingQueue<>(2000);
        
        executorService = Executors.newFixedThreadPool(6);
        
        establishVpn();
        
        return START_STICKY;
    }

    private void establishVpn() {
        try {
            Builder builder = new Builder()
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                .addRoute(VPN_ROUTE, ROUTE_PREFIX_LENGTH)
                .setSession("NetSniff-PerApp")
                .setMtu(MAX_PACKET_SIZE)
                .setBlocking(false);

            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");

            for (String pkg : ALLOWED_PACKAGES) {
                try {
                    builder.addAllowedApplication(pkg);
                    Log.d(TAG, "Added to VPN: " + pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Cannot add app to VPN: " + pkg);
                }
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }

            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            running.set(true);
            
            vpnThread = new Thread(new VPNInputRunnable(), "VPN-Input");
            tcpForwardThread = new Thread(new TcpForwardRunnable(), "TCP-Forward");
            udpForwardThread = new Thread(new UdpForwardRunnable(), "UDP-Forward");
            icmpForwardThread = new Thread(new IcmpForwardRunnable(), "ICMP-Forward");
            notificationThread = new Thread(new PacketNotificationRunnable(), "VPN-Notify");
            usageStatsThread = new Thread(new UsageStatsRunnable(), "UsageStats-Tracker");
            
            vpnThread.start();
            tcpForwardThread.start();
            udpForwardThread.start();
            icmpForwardThread.start();
            notificationThread.start();
            usageStatsThread.start();
            
            Log.d(TAG, "Per-app VPN established successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            stopVpnGracefully();
        }
    }

    /**
     * UsageStats tracker thread (from reference code)
     */
    private class UsageStatsRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "UsageStats tracker thread started");
            
            while (running.get() && !Thread.interrupted()) {
                try {
                    updateRecentForegroundApps();
                    Thread.sleep(2000); // Update every 2 seconds
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error tracking usage stats", e);
                }
            }
            
            Log.d(TAG, "UsageStats tracker thread stopped");
        }
        
        private void updateRecentForegroundApps() {
            if (usageStatsManager == null) return;
            
            long now = System.currentTimeMillis();
            long begin = now - (5 * 60 * 1000); // Look back 5 minutes
            
            UsageEvents events = usageStatsManager.queryEvents(begin, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            Map<String, Long> lastSeen = new HashMap<>();
            
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        ev.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    lastSeen.put(ev.getPackageName(), ev.getTimeStamp());
                }
            }
            
            synchronized (uidCacheLock) {
                recentForegroundApps.clear();
                recentForegroundApps.putAll(lastSeen);
            }
        }
    }

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
                            ByteBuffer copy = ByteBuffer.allocate(length);
                            copy.put(packet.array(), 0, length);
                            copy.flip();
                            
                            handleOutgoingPacket(copy);
                        } else if (length < 0) {
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

    private void handleOutgoingPacket(ByteBuffer packet) {
        try {
            packet.position(0);
            int versionAndIHL = packet.get() & 0xFF;
            int version = (versionAndIHL >> 4) & 0xF;
            if (version != 4) return;
            
            int ihl = (versionAndIHL & 0xF) * 4;
            packet.position(9);
            int protocol = packet.get() & 0xFF;
            
            if (protocol != 1 && protocol != 6 && protocol != 17) return; // ICMP, TCP, UDP only
            
            packet.position(12);
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            packet.get(sourceAddr);
            packet.get(destAddr);
            
            String sourceIp = ipToString(sourceAddr);
            String destIp = ipToString(destAddr);
            
            String key;
            if (protocol == 1) { // ICMP
                key = "1:" + sourceIp + ":0:" + destIp + ":0";
            } else {
                packet.position(ihl);
                int sourcePort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                int destPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                key = protocol + ":" + sourceIp + ":" + sourcePort + ":" + destIp + ":" + destPort;
            }
            
            packet.position(0);
            long pktNum = packetCounter.incrementAndGet();
            Packet pkt = new Packet(packet, key, false, pktNum, protocol);
            
            if (!outgoingPackets.offer(pkt)) {
                Log.w(TAG, "Outgoing queue full");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling outgoing packet", e);
        }
    }

    private class TcpForwardRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "TCP forward thread started");
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        Packet packet = outgoingPackets.poll(100, TimeUnit.MILLISECONDS);
                        if (packet != null && !packet.isIncoming && packet.protocol == 6) {
                            forwardTcpPacket(packet);
                        }
                        
                        if (tcpSelector.selectNow() > 0) {
                            Iterator<SelectionKey> keys = tcpSelector.selectedKeys().iterator();
                            while (keys.hasNext()) {
                                SelectionKey key = keys.next();
                                keys.remove();
                                
                                if (key.isValid() && key.isReadable()) {
                                    handleTcpResponse(key);
                                }
                            }
                        }
                        
                        cleanupStaleConnections();
                        
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.e(TAG, "Error in TCP forward", e);
                        }
                    }
                }
            } finally {
                closeAllTcpConnections();
                Log.d(TAG, "TCP forward thread stopped");
            }
        }
        
        private void forwardTcpPacket(Packet packet) throws IOException {
            TcpConnection conn = getOrCreateTcpConnection(packet);
            if (conn != null && conn.channel != null && conn.channel.isConnected()) {
                ByteBuffer data = packet.data;
                int ihl = (data.get(0) & 0xF) * 4;
                int totalLength = ((data.get(2) & 0xFF) << 8) | (data.get(3) & 0xFF);
                int tcpDataStart = ihl + 20;
                
                if (totalLength > tcpDataStart) {
                    data.position(tcpDataStart);
                    int payloadLength = totalLength - tcpDataStart;
                    ByteBuffer payload = ByteBuffer.allocate(payloadLength);
                    payload.put(data.array(), tcpDataStart, payloadLength);
                    payload.flip();
                    
                    conn.channel.write(payload);
                    conn.lastActivity = System.currentTimeMillis();
                }
            }
        }
        
        private TcpConnection getOrCreateTcpConnection(Packet packet) {
            TcpConnection conn = tcpConnections.get(packet.key);
            if (conn == null) {
                try {
                    ByteBuffer data = packet.data;
                    data.position(12);
                    byte[] sourceAddr = new byte[4];
                    byte[] destAddr = new byte[4];
                    data.get(sourceAddr);
                    data.get(destAddr);
                    
                    int ihl = (data.get(0) & 0xF) * 4;
                    data.position(ihl);
                    int sourcePort = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                    int destPort = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                    
                    String sourceIp = ipToString(sourceAddr);
                    String destIp = ipToString(destAddr);
                    
                    int uid = getMostLikelyActiveUid();
                    
                    conn = new TcpConnection(sourceIp, sourcePort, destIp, destPort, uid);
                    conn.channel = SocketChannel.open();
                    conn.channel.configureBlocking(false);
                    protect(conn.channel.socket());
                    
                    InetSocketAddress remote = new InetSocketAddress(destIp, destPort);
                    conn.channel.connect(remote);
                    conn.channel.register(tcpSelector, SelectionKey.OP_READ, conn);
                    
                    tcpConnections.put(packet.key, conn);
                    Log.d(TAG, "TCP connection: " + sourceIp + ":" + sourcePort + " -> " + destIp + ":" + destPort + " (UID: " + uid + ")");
                    
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create TCP connection", e);
                    return null;
                }
            }
            return conn;
        }
        
        private void handleTcpResponse(SelectionKey key) {
            TcpConnection conn = (TcpConnection) key.attachment();
            try {
                conn.readBuffer.clear();
                int bytesRead = conn.channel.read(conn.readBuffer);
                
                if (bytesRead > 0) {
                    conn.readBuffer.flip();
                    ByteBuffer response = buildTcpResponse(conn, conn.readBuffer);
                    String responseKey = "6:" + conn.destIp + ":" + conn.destPort + ":" + conn.sourceIp + ":" + conn.sourcePort;
                    long pktNum = packetCounter.incrementAndGet();
                    Packet responsePacket = new Packet(response, responseKey, true, pktNum, 6);
                    
                    if (!incomingPackets.offer(responsePacket)) {
                        Log.w(TAG, "Incoming queue full");
                    }
                    
                    conn.lastActivity = System.currentTimeMillis();
                } else if (bytesRead < 0) {
                    closeTcpConnection(conn);
                }
            } catch (IOException e) {
                closeTcpConnection(conn);
            }
        }
    }

    private class UdpForwardRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "UDP forward thread started");
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        Packet packet = outgoingPackets.poll(100, TimeUnit.MILLISECONDS);
                        if (packet != null && !packet.isIncoming && packet.protocol == 17) {
                            forwardUdpPacket(packet);
                        }
                        
                        if (udpSelector.selectNow() > 0) {
                            Iterator<SelectionKey> keys = udpSelector.selectedKeys().iterator();
                            while (keys.hasNext()) {
                                SelectionKey key = keys.next();
                                keys.remove();
                                
                                if (key.isValid() && key.isReadable()) {
                                    handleUdpResponse(key);
                                }
                            }
                        }
                        
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.e(TAG, "Error in UDP forward", e);
                        }
                    }
                }
            } finally {
                closeAllUdpConnections();
                Log.d(TAG, "UDP forward thread stopped");
            }
        }
        
        private void forwardUdpPacket(Packet packet) throws IOException {
            UdpConnection conn = getOrCreateUdpConnection(packet);
            if (conn != null && conn.channel != null) {
                ByteBuffer data = packet.data;
                int ihl = (data.get(0) & 0xF) * 4;
                int udpDataStart = ihl + 8;
                int totalLength = ((data.get(2) & 0xFF) << 8) | (data.get(3) & 0xFF);
                
                if (totalLength > udpDataStart) {
                    data.position(udpDataStart);
                    int payloadLength = totalLength - udpDataStart;
                    ByteBuffer payload = ByteBuffer.allocate(payloadLength);
                    payload.put(data.array(), udpDataStart, payloadLength);
                    payload.flip();
                    
                    InetSocketAddress dest = new InetSocketAddress(conn.destIp, conn.destPort);
                    conn.channel.send(payload, dest);
                    conn.lastActivity = System.currentTimeMillis();
                }
            }
        }
        
        private UdpConnection getOrCreateUdpConnection(Packet packet) {
            UdpConnection conn = udpConnections.get(packet.key);
            if (conn == null) {
                try {
                    ByteBuffer data = packet.data;
                    data.position(12);
                    byte[] sourceAddr = new byte[4];
                    byte[] destAddr = new byte[4];
                    data.get(sourceAddr);
                    data.get(destAddr);
                    
                    int ihl = (data.get(0) & 0xF) * 4;
                    data.position(ihl);
                    int sourcePort = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                    int destPort = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                    
                    String sourceIp = ipToString(sourceAddr);
                    String destIp = ipToString(destAddr);
                    
                    int uid = getMostLikelyActiveUid();
                    
                    conn = new UdpConnection(sourceIp, sourcePort, destIp, destPort, uid);
                    conn.channel = DatagramChannel.open();
                    conn.channel.configureBlocking(false);
                    protect(conn.channel.socket());
                    conn.channel.register(udpSelector, SelectionKey.OP_READ, conn);
                    
                    udpConnections.put(packet.key, conn);
                    Log.d(TAG, "UDP connection: " + sourceIp + ":" + sourcePort + " -> " + destIp + ":" + destPort + " (UID: " + uid + ")");
                    
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create UDP connection", e);
                    return null;
                }
            }
            return conn;
        }
        
        private void handleUdpResponse(SelectionKey key) {
            UdpConnection conn = (UdpConnection) key.attachment();
            try {
                ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
                InetSocketAddress sender = (InetSocketAddress) conn.channel.receive(buffer);
                
                if (sender != null) {
                    buffer.flip();
                    ByteBuffer response = buildUdpResponse(conn, buffer);
                    String responseKey = "17:" + conn.destIp + ":" + conn.destPort + ":" + conn.sourceIp + ":" + conn.sourcePort;
                    long pktNum = packetCounter.incrementAndGet();
                    Packet responsePacket = new Packet(response, responseKey, true, pktNum, 17);
                    
                    if (!incomingPackets.offer(responsePacket)) {
                        Log.w(TAG, "Incoming queue full");
                    }
                    
                    conn.lastActivity = System.currentTimeMillis();
                }
            } catch (IOException e) {
                closeUdpConnection(conn);
            }
        }
    }

    /**
     * ICMP Forward Thread - handles ICMP packets (ping, etc)
     */
    private class IcmpForwardRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "ICMP forward thread started");
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        Packet packet = outgoingPackets.poll(100, TimeUnit.MILLISECONDS);
                        if (packet != null && !packet.isIncoming && packet.protocol == 1) {
                            // ICMP packets are just logged/notified, not forwarded
                            // (forwarding ICMP requires raw sockets which need root)
                            Log.d(TAG, "ICMP packet detected: " + packet.key);
                        }
                        
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.e(TAG, "Error in ICMP forward", e);
                        }
                    }
                }
            } finally {
                Log.d(TAG, "ICMP forward thread stopped");
            }
        }
    }

    private class PacketNotificationRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Packet notification thread started");
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    try {
                        Packet outgoing = outgoingPackets.poll(50, TimeUnit.MILLISECONDS);
                        if (outgoing != null) {
                            notifyPacket(outgoing.data, "outgoing", outgoing.packetNumber);
                        }
                        
                        Packet incoming = incomingPackets.poll(50, TimeUnit.MILLISECONDS);
                        if (incoming != null) {
                            notifyPacket(incoming.data, "incoming", incoming.packetNumber);
                            out.write(incoming.data.array(), 0, incoming.data.limit());
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

    private ByteBuffer buildTcpResponse(TcpConnection conn, ByteBuffer payload) {
        int payloadSize = payload.remaining();
        int totalSize = 20 + 20 + payloadSize;
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        packet.put((byte) 0x45);
        packet.put((byte) 0);
        packet.putShort((short) totalSize);
        packet.putShort((short) 0);
        packet.putShort((short) 0);
        packet.put((byte) 64);
        packet.put((byte) 6);
        packet.putShort((short) 0);
        packet.put(ipToBytes(conn.destIp));
        packet.put(ipToBytes(conn.sourceIp));
        
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putInt(0);
        packet.putInt(0);
        packet.putShort((short) 0x5000);
        packet.putShort((short) 65535);
        packet.putShort((short) 0);
        packet.putShort((short) 0);
        
        packet.put(payload);
        packet.flip();
        return packet;
    }

    private ByteBuffer buildUdpResponse(UdpConnection conn, ByteBuffer payload) {
        int payloadSize = payload.remaining();
        int totalSize = 20 + 8 + payloadSize;
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        packet.put((byte) 0x45);
        packet.put((byte) 0);
        packet.putShort((short) totalSize);
        packet.putShort((short) 0);
        packet.putShort((short) 0);
        packet.put((byte) 64);
        packet.put((byte) 17);
        packet.putShort((short) 0);
        packet.put(ipToBytes(conn.destIp));
        packet.put(ipToBytes(conn.sourceIp));
        
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putShort((short) (8 + payloadSize));
        packet.putShort((short) 0);
        
        packet.put(payload);
        packet.flip();
        return packet;
    }

    private void notifyPacket(ByteBuffer packet, String direction, long packetNumber) {
        try {
            packet.position(0);
            
            int versionAndIHL = packet.get() & 0xFF;
            int version = (versionAndIHL >> 4) & 0xF;
            if (version != 4) return;
            
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
            
            String sourcePort = "";
            String destPort = "";
            int uid = -1;
            
            if (protocol == 6 || protocol == 17) {
                packet.position(ihl);
                int srcPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                int dstPort = ((packet.get() & 0xFF) << 8) | (packet.get() & 0xFF);
                sourcePort = String.valueOf(srcPort);
                destPort = String.valueOf(dstPort);
                
                if (direction.equals("outgoing")) {
                    uid = getMostLikelyActiveUid();
                }
            } else if (protocol == 1) {
                // ICMP has no ports
                sourcePort = "0";
                destPort = "0";
                if (direction.equals("outgoing")) {
                    uid = getMostLikelyActiveUid();
                }
            }
            
            String appName = getAppNameForUid(uid);
            String packageName = getPackageNameForUid(uid);
            
            JSObject packetInfo = new JSObject();
            packetInfo.put("packetNumber", packetNumber);
            packetInfo.put("source", ipToString(sourceAddr) + (sourcePort.isEmpty() ? "" : ":" + sourcePort));
            packetInfo.put("destination", ipToString(destAddr) + (destPort.isEmpty() ? "" : ":" + destPort));
            packetInfo.put("protocol", getProtocolName(protocol));
            packetInfo.put("direction", direction);
            packetInfo.put("size", totalLength);
            packetInfo.put("appName", appName);
            packetInfo.put("packageName", packageName);
            packetInfo.put("uid", uid);
            
            StringBuilder payload = new StringBuilder();
            int payloadStart = ihl;
            if (protocol == 6) payloadStart += 20;
            else if (protocol == 17) payloadStart += 8;
            else if (protocol == 1) payloadStart += 8;
            
            int payloadLength = Math.min(totalLength - payloadStart, 64);
            packet.position(payloadStart);
            for (int i = 0; i < payloadLength && packet.hasRemaining(); i++) {
                payload.append(String.format("%02X ", packet.get()));
                if ((i + 1) % 16 == 0) payload.append("\n");
            }
            packetInfo.put("payload", payload.toString().trim());
            
            ToyVpnPlugin.notifyPacketCaptured(packetInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error notifying packet", e);
        }
    }

    private void cleanupStaleConnections() {
        long now = System.currentTimeMillis();
        long timeout = 60000;
        
        tcpConnections.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastActivity > timeout) {
                closeTcpConnection(entry.getValue());
                return true;
            }
            return false;
        });
        
        udpConnections.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastActivity > timeout) {
                closeUdpConnection(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private void closeTcpConnection(TcpConnection conn) {
        try {
            if (conn.channel != null) conn.channel.close();
        } catch (IOException ignored) {}
    }

    private void closeUdpConnection(UdpConnection conn) {
        try {
            if (conn.channel != null) conn.channel.close();
        } catch (IOException ignored) {}
    }

    private void closeAllTcpConnections() {
        for (TcpConnection conn : tcpConnections.values()) {
            closeTcpConnection(conn);
        }
        tcpConnections.clear();
    }

    private void closeAllUdpConnections() {
        for (UdpConnection conn : udpConnections.values()) {
            closeUdpConnection(conn);
        }
        udpConnections.clear();
    }

    private String ipToString(byte[] addr) {
        return String.format("%d.%d.%d.%d", 
            addr[0] & 0xFF, addr[1] & 0xFF, addr[2] & 0xFF, addr[3] & 0xFF);
    }

    private byte[] ipToBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    private String getProtocolName(int protocol) {
        switch (protocol) {
            case 1: return "ICMP";
            case 6: return "TCP";
            case 17: return "UDP";
            default: return "IP(" + protocol + ")";
        }
    }

    public void stopVpnGracefully() {
        if (shuttingDown.getAndSet(true)) return;
        
        Log.d(TAG, "Stopping per-app VPN");
        running.set(false);
        
        new Thread(() -> {
            try {
                if (vpnThread != null) vpnThread.interrupt();
                if (tcpForwardThread != null) tcpForwardThread.interrupt();
                if (udpForwardThread != null) udpForwardThread.interrupt();
                if (icmpForwardThread != null) icmpForwardThread.interrupt();
                if (notificationThread != null) notificationThread.interrupt();
                if (usageStatsThread != null) usageStatsThread.interrupt();
                
                if (vpnThread != null) vpnThread.join(1000);
                if (tcpForwardThread != null) tcpForwardThread.join(1000);
                if (udpForwardThread != null) udpForwardThread.join(1000);
                if (icmpForwardThread != null) icmpForwardThread.join(1000);
                if (notificationThread != null) notificationThread.join(1000);
                if (usageStatsThread != null) usageStatsThread.join(1000);
                
                if (tcpSelector != null && tcpSelector.isOpen()) tcpSelector.close();
                if (udpSelector != null && udpSelector.isOpen()) udpSelector.close();
                
                closeAllTcpConnections();
                closeAllUdpConnections();
                
                if (vpnInterface != null) {
                    try { vpnInterface.close(); } catch (IOException ignored) {}
                    vpnInterface = null;
                }
                
                if (executorService != null) {
                    executorService.shutdown();
                    executorService.awaitTermination(1, TimeUnit.SECONDS);
                }
                
                if (outgoingPackets != null) outgoingPackets.clear();
                if (incomingPackets != null) incomingPackets.clear();
                
                ToyVpnPlugin.notifyVpnStopped();
                Log.d(TAG, "VPN shutdown complete");
                
            } catch (Exception e) {
                Log.e(TAG, "Error during shutdown", e);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }, "VPN-Shutdown").start();
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown.get()) stopVpnGracefully();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVpnGracefully();
        super.onRevoke();
    }
}