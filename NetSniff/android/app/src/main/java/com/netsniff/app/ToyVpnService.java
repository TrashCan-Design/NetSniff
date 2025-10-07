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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ToyVpnService extends VpnService {
    private static final String TAG = "ToyVpnService";
    private static final int MAX_PACKET_SIZE = 32767;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    
    private static final int NOTIFICATION_ID = 1234;
    private static final String CHANNEL_ID = "NetSniffVpnChannel";
    
    public static final String ACTION_CONNECT = "com.netsniff.app.START";
    public static final String ACTION_DISCONNECT = "com.netsniff.app.STOP";
    
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.google.android.googlequicksearchbox",
        "com.google.android.youtube",
        "com.google.android.gm"
    ));
    
    // TCP States
    private static final int TCP_IDLE = 0;
    private static final int TCP_SYN_SENT = 1;
    private static final int TCP_SYN_RECEIVED = 2;
    private static final int TCP_ESTABLISHED = 3;
    private static final int TCP_FIN_WAIT = 4;
    private static final int TCP_CLOSE_WAIT = 5;
    private static final int TCP_CLOSING = 6;
    private static final int TCP_CLOSED = 7;
    
    private ParcelFileDescriptor vpnInterface = null;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    private Thread vpnThread;
    private Thread writeThread;
    private Selector selector;
    
    private ConcurrentHashMap<String, TcpConnection> tcpConnections;
    private ConcurrentHashMap<String, UdpConnection> udpConnections;
    private Set<Integer> allowedUids;
    
    private BlockingQueue<ByteBuffer> writeQueue;
    private AtomicLong packetCounter = new AtomicLong(0);
    
    private PackageManager packageManager;
    private UsageStatsManager usageStatsManager;
    private Map<Integer, List<String>> uidToPackagesMap;
    private Map<Integer, String> uidToAppNameMap;
    private Map<String, Long> recentForegroundApps;
    private final Object uidCacheLock = new Object();

    private static class TcpConnection {
        SocketChannel channel;
        String key;
        String sourceIp;
        int sourcePort;
        String destIp;
        int destPort;
        long lastActivity;
        int uid;
        
        // TCP state
        int state;
        long localSeq;
        long remoteSeq;
        long localSeqStart;
        long remoteSeqStart;
        int sendWindow;
        int recvWindow;
        
        // Buffers
        ByteBuffer readBuffer;
        ByteBuffer writeBuffer;
        Queue<ByteBuffer> pendingWrites;

        TcpConnection(String key, String sourceIp, int sourcePort, String destIp, int destPort, int uid) {
            this.key = key;
            this.sourceIp = sourceIp;
            this.sourcePort = sourcePort;
            this.destIp = destIp;
            this.destPort = destPort;
            this.uid = uid;
            this.lastActivity = System.currentTimeMillis();
            this.readBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
            this.writeBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
            this.pendingWrites = new ConcurrentLinkedQueue<>();
            this.state = TCP_IDLE;
            this.localSeq = (long) (Math.random() * 0xFFFFFFFFL);
            this.localSeqStart = this.localSeq;
            this.remoteSeq = 0;
            this.remoteSeqStart = 0;
            this.sendWindow = 65535;
            this.recvWindow = 65535;
        }
    }
    
    private static class UdpConnection {
        DatagramChannel channel;
        String key;
        String sourceIp;
        int sourcePort;
        String destIp;
        int destPort;
        long lastActivity;
        int uid;
        
        UdpConnection(String key, String sourceIp, int sourcePort, String destIp, int destPort, int uid) {
            this.key = key;
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
        writeQueue = new LinkedBlockingQueue<>(5000);
        
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
    
    private String getAppNameForUid(int uid) {
        synchronized (uidCacheLock) {
            String appName = uidToAppNameMap.get(uid);
            if (appName != null) {
                return appName;
            }
        }
        
        if (uid == 1000) return "Android System";
        
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
                try {
                    ApplicationInfo ai = packageManager.getApplicationInfo(mostRecentPackage, 0);
                    return ai.uid;
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore
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
        establishVpn();
        
        return START_STICKY;
    }

    private void establishVpn() {
        try {
            Builder builder = new Builder()
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
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

            selector = Selector.open();
            running.set(true);
            
            // Start threads
            vpnThread = new Thread(new VPNRunnable(), "VPN-Thread");
            vpnThread.start();
            
            writeThread = new Thread(new WriteRunnable(), "Write-Thread");
            writeThread.start();
            
            // Start usage stats tracker
            new Thread(new UsageStatsRunnable(), "UsageStats-Tracker").start();
            
            Log.d(TAG, "Per-app VPN established successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            stopVpnGracefully();
        }
    }

    private class VPNRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "VPN thread started");
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            byte[] packet = new byte[MAX_PACKET_SIZE];

            try {
                while (running.get() && !Thread.interrupted()) {
                    int length = in.read(packet);
                    
                    if (length > 0) {
                        handlePacket(packet, length);
                    } else if (length < 0) {
                        break;
                    }
                    
                    // Check selector for socket events
                    processSocketEvents();
                    
                    // Cleanup stale connections
                    cleanupConnections();
                }
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "Error in VPN thread", e);
                }
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                Log.d(TAG, "VPN thread stopped");
            }
        }
    }
    
    private void handlePacket(byte[] packet, int length) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);
            int versionAndIHL = buffer.get(0) & 0xFF;
            int version = (versionAndIHL >> 4) & 0xF;
            
            if (version != 4) return; // Only IPv4 for now
            
            int ihl = (versionAndIHL & 0xF) * 4;
            int protocol = buffer.get(9) & 0xFF;
            
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            buffer.position(12);
            buffer.get(sourceAddr);
            buffer.get(destAddr);
            
            String sourceIp = ipToString(sourceAddr);
            String destIp = ipToString(destAddr);
            
            if (protocol == 6) { // TCP
                handleTcpPacket(buffer, ihl, sourceIp, destIp, length);
            } else if (protocol == 17) { // UDP
                handleUdpPacket(buffer, ihl, sourceIp, destIp, length);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling packet", e);
        }
    }
    
    private void handleTcpPacket(ByteBuffer buffer, int ihl, String sourceIp, String destIp, int totalLength) {
        try {
            buffer.position(ihl);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            long seq = buffer.getInt() & 0xFFFFFFFFL;
            long ackSeq = buffer.getInt() & 0xFFFFFFFFL;
            
            int dataOffsetAndFlags = buffer.getShort() & 0xFFFF;
            int dataOffset = (dataOffsetAndFlags >> 12) * 4;
            
            boolean syn = (buffer.get(ihl + 13) & 0x02) != 0;
            boolean ack = (buffer.get(ihl + 13) & 0x10) != 0;
            boolean fin = (buffer.get(ihl + 13) & 0x01) != 0;
            boolean rst = (buffer.get(ihl + 13) & 0x04) != 0;
            boolean psh = (buffer.get(ihl + 13) & 0x08) != 0;
            
            int window = buffer.getShort(ihl + 14) & 0xFFFF;
            
            String key = "6:" + sourceIp + ":" + sourcePort + ":" + destIp + ":" + destPort;
            
            // Get or create connection
            TcpConnection conn = tcpConnections.get(key);
            
            if (rst) {
                if (conn != null) {
                    closeTcpConnection(conn);
                    tcpConnections.remove(key);
                }
                return;
            }
            
            if (syn && !ack) {
                // New connection
                int uid = getMostLikelyActiveUid();
                conn = new TcpConnection(key, sourceIp, sourcePort, destIp, destPort, uid);
                conn.remoteSeq = seq;
                conn.remoteSeqStart = seq;
                conn.sendWindow = window;
                
                try {
                    conn.channel = SocketChannel.open();
                    conn.channel.configureBlocking(false);
                    conn.channel.socket().setTcpNoDelay(true);
                    protect(conn.channel.socket());
                    
                    InetSocketAddress remote = new InetSocketAddress(destIp, destPort);
                    conn.channel.connect(remote);
                    conn.channel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ, conn);
                    
                    conn.state = TCP_SYN_SENT;
                    tcpConnections.put(key, conn);
                    
                    Log.d(TAG, "New TCP connection: " + key);
                    notifyPacket(buffer.array(), totalLength, "outgoing", uid, sourceIp, sourcePort, destIp, destPort, 6);
                    
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create TCP socket", e);
                    sendTcpReset(sourceIp, sourcePort, destIp, destPort, seq + 1, 0);
                }
                return;
            }
            
            if (conn == null) {
                Log.w(TAG, "No connection for packet: " + key);
                return;
            }
            
            conn.lastActivity = System.currentTimeMillis();
            conn.sendWindow = window;
            
            // Handle connection states
            if (ack && conn.state == TCP_SYN_RECEIVED) {
                // ACK of our SYN-ACK
                conn.state = TCP_ESTABLISHED;
                Log.d(TAG, "TCP connection established: " + key);
            }
            
            if (conn.state == TCP_ESTABLISHED || conn.state == TCP_CLOSE_WAIT) {
                // Handle data
                int headerSize = ihl + dataOffset;
                int dataSize = totalLength - headerSize;
                
                if (dataSize > 0 && !fin) {
                    conn.remoteSeq = seq + dataSize;
                    
                    buffer.position(headerSize);
                    byte[] data = new byte[dataSize];
                    buffer.get(data);
                    
                    ByteBuffer dataBuffer = ByteBuffer.wrap(data);
                    conn.pendingWrites.offer(dataBuffer);
                    
                    // Update selector
                    SelectionKey key2 = conn.channel.keyFor(selector);
                    if (key2 != null && key2.isValid()) {
                        key2.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    }
                }
                
                if (fin) {
                    conn.remoteSeq = seq + 1;
                    conn.state = TCP_CLOSE_WAIT;
                    sendTcpAck(conn);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling TCP packet", e);
        }
    }
    
    private void handleUdpPacket(ByteBuffer buffer, int ihl, String sourceIp, String destIp, int totalLength) {
        try {
            buffer.position(ihl);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            int length = buffer.getShort() & 0xFFFF;
            
            String key = "17:" + sourceIp + ":" + sourcePort + ":" + destIp + ":" + destPort;
            
            UdpConnection conn = udpConnections.get(key);
            if (conn == null) {
                int uid = getMostLikelyActiveUid();
                conn = new UdpConnection(key, sourceIp, sourcePort, destIp, destPort, uid);
                
                try {
                    conn.channel = DatagramChannel.open();
                    conn.channel.configureBlocking(false);
                    protect(conn.channel.socket());
                    conn.channel.register(selector, SelectionKey.OP_READ, conn);
                    
                    udpConnections.put(key, conn);
                    Log.d(TAG, "New UDP connection: " + key);
                    
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create UDP socket", e);
                    return;
                }
            }
            
            conn.lastActivity = System.currentTimeMillis();
            
            // Forward UDP data
            int dataSize = length - 8;
            if (dataSize > 0) {
                buffer.position(ihl + 8);
                byte[] data = new byte[dataSize];
                buffer.get(data);
                
                InetSocketAddress dest = new InetSocketAddress(destIp, destPort);
                conn.channel.send(ByteBuffer.wrap(data), dest);
                
                notifyPacket(buffer.array(), totalLength, "outgoing", conn.uid, 
                    sourceIp, sourcePort, destIp, destPort, 17);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling UDP packet", e);
        }
    }
    
    private void processSocketEvents() {
        try {
            if (selector.selectNow() > 0) {
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    
                    if (!key.isValid()) continue;
                    
                    Object attachment = key.attachment();
                    
                    if (attachment instanceof TcpConnection) {
                        TcpConnection conn = (TcpConnection) attachment;
                        
                        if (key.isConnectable()) {
                            handleTcpConnect(conn, key);
                        }
                        if (key.isReadable()) {
                            handleTcpRead(conn);
                        }
                        if (key.isWritable()) {
                            handleTcpWrite(conn, key);
                        }
                    } else if (attachment instanceof UdpConnection) {
                        UdpConnection conn = (UdpConnection) attachment;
                        
                        if (key.isReadable()) {
                            handleUdpRead(conn);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing socket events", e);
        }
    }
    
    private void handleTcpConnect(TcpConnection conn, SelectionKey key) {
        try {
            if (conn.channel.finishConnect()) {
                Log.d(TAG, "TCP connected: " + conn.key);
                
                // Send SYN-ACK to client
                conn.localSeq++;
                conn.remoteSeq++;
                conn.state = TCP_SYN_RECEIVED;
                
                sendTcpSynAck(conn);
                
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to complete connection", e);
            closeTcpConnection(conn);
            tcpConnections.remove(conn.key);
        }
    }
    
    private void handleTcpRead(TcpConnection conn) {
        try {
            conn.readBuffer.clear();
            int bytesRead = conn.channel.read(conn.readBuffer);
            
            if (bytesRead > 0) {
                conn.readBuffer.flip();
                byte[] data = new byte[conn.readBuffer.remaining()];
                conn.readBuffer.get(data);
                
                // Send data back to client
                ByteBuffer response = buildTcpPacket(conn, data, data.length, 
                    false, true, false, false, conn.localSeq, conn.remoteSeq);
                
                conn.localSeq += data.length;
                writeQueue.offer(response);
                
                notifyPacket(data, data.length, "incoming", conn.uid, 
                    conn.destIp, conn.destPort, conn.sourceIp, conn.sourcePort, 6);
                    
            } else if (bytesRead < 0) {
                // Remote closed
                Log.d(TAG, "TCP remote closed: " + conn.key);
                sendTcpFin(conn);
                closeTcpConnection(conn);
                tcpConnections.remove(conn.key);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading from TCP socket", e);
            closeTcpConnection(conn);
            tcpConnections.remove(conn.key);
        }
    }
    
    private void handleTcpWrite(TcpConnection conn, SelectionKey key) {
        try {
            ByteBuffer data = conn.pendingWrites.poll();
            if (data != null) {
                conn.channel.write(data);
                
                if (data.hasRemaining()) {
                    conn.pendingWrites.offer(data);
                } else {
                    // Send ACK
                    sendTcpAck(conn);
                }
            }
            
            if (conn.pendingWrites.isEmpty()) {
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing to TCP socket", e);
            closeTcpConnection(conn);
            tcpConnections.remove(conn.key);
        }
    }
    
    private void handleUdpRead(UdpConnection conn) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
            InetSocketAddress sender = (InetSocketAddress) conn.channel.receive(buffer);
            
            if (sender != null) {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                
                // Build UDP response packet
                ByteBuffer response = buildUdpPacket(conn, data, data.length);
                writeQueue.offer(response);
                
                notifyPacket(data, data.length, "incoming", conn.uid,
                    conn.destIp, conn.destPort, conn.sourceIp, conn.sourcePort, 17);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading from UDP socket", e);
            closeUdpConnection(conn);
            udpConnections.remove(conn.key);
        }
    }
    
    private void sendTcpSynAck(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, 
            true, true, false, false, conn.localSeq, conn.remoteSeq);
        writeQueue.offer(packet);
    }
    
    private void sendTcpAck(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, 
            false, true, false, false, conn.localSeq, conn.remoteSeq);
        writeQueue.offer(packet);
    }
    
    private void sendTcpFin(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, 
            false, true, true, false, conn.localSeq, conn.remoteSeq);
        writeQueue.offer(packet);
    }
    
    private void sendTcpReset(String sourceIp, int sourcePort, String destIp, int destPort, long seq, long ack) {
        // Build minimal reset packet
        ByteBuffer packet = ByteBuffer.allocate(60);
        // Implementation simplified - full implementation would build proper RST packet
        writeQueue.offer(packet);
    }
    
    private ByteBuffer buildTcpPacket(TcpConnection conn, byte[] payload, int payloadSize,
                                      boolean syn, boolean ack, boolean fin, boolean rst,
                                      long seqNum, long ackNum) {
        int totalSize = 20 + 20 + payloadSize;
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // IP Header
        packet.put((byte) 0x45);
        packet.put((byte) 0x00);
        packet.putShort((short) totalSize);
        packet.putShort((short) 0);
        packet.putShort((short) 0x4000);
        packet.put((byte) 64);
        packet.put((byte) 6);
        packet.putShort((short) 0);
        packet.put(ipToBytes(conn.destIp));
        packet.put(ipToBytes(conn.sourceIp));
        
        int ipChecksumPos = 10;
        packet.putShort(ipChecksumPos, PacketUtils.calculateIPChecksum(packet, 0));
        
        // TCP Header
        int tcpStart = packet.position();
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putInt((int) seqNum);
        packet.putInt((int) ackNum);
        
        int flags = 0x5000;
        if (syn) flags |= 0x0002;
        if (ack) flags |= 0x0010;
        if (fin) flags |= 0x0001;
        if (rst) flags |= 0x0004;
        packet.putShort((short) flags);
        
        packet.putShort((short) conn.recvWindow);
        packet.putShort((short) 0);
        packet.putShort((short) 0);
        
        if (payload != null && payloadSize > 0) {
            packet.put(payload, 0, payloadSize);
        }
        
        int tcpChecksumPos = tcpStart + 16;
        packet.putShort(tcpChecksumPos, 
            PacketUtils.calculateTCPChecksum(packet, 0, tcpStart, 20 + payloadSize));
        
        packet.flip();
        return packet;
    }
    
    private ByteBuffer buildUdpPacket(UdpConnection conn, byte[] payload, int payloadSize) {
        int totalSize = 20 + 8 + payloadSize;
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // IP Header
        packet.put((byte) 0x45);
        packet.put((byte) 0x00);
        packet.putShort((short) totalSize);
        packet.putShort((short) 0);
        packet.putShort((short) 0x4000);
        packet.put((byte) 64);
        packet.put((byte) 17);
        packet.putShort((short) 0);
        packet.put(ipToBytes(conn.destIp));
        packet.put(ipToBytes(conn.sourceIp));
        
        packet.putShort(10, PacketUtils.calculateIPChecksum(packet, 0));
        
        // UDP Header
        int udpStart = packet.position();
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putShort((short) (8 + payloadSize));
        packet.putShort((short) 0);
        
        if (payload != null && payloadSize > 0) {
            packet.put(payload);
        }
        
        packet.putShort(udpStart + 6, 
            PacketUtils.calculateUDPChecksum(packet, 0, udpStart, 8 + payloadSize));
        
        packet.flip();
        return packet;
    }
    
    private class WriteRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Write thread started");
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            
            try {
                while (running.get() && !Thread.interrupted()) {
                    ByteBuffer packet = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (packet != null) {
                        out.write(packet.array(), 0, packet.limit());
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "Error in write thread", e);
                }
            } finally {
                try { out.close(); } catch (IOException ignored) {}
                Log.d(TAG, "Write thread stopped");
            }
        }
    }
    
    private class UsageStatsRunnable implements Runnable {
        @Override
        public void run() {
            while (running.get() && !Thread.interrupted()) {
                try {
                    updateRecentForegroundApps();
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error tracking usage stats", e);
                }
            }
        }
        
        private void updateRecentForegroundApps() {
            if (usageStatsManager == null) return;
            
            long now = System.currentTimeMillis();
            long begin = now - (5 * 60 * 1000);
            
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
    
    private void cleanupConnections() {
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
    
    private void notifyPacket(byte[] data, int length, String direction, int uid,
                             String sourceIp, int sourcePort, String destIp, int destPort, int protocol) {
        try {
            String appName = getAppNameForUid(uid);
            String packageName = getPackageNameForUid(uid);
            long pktNum = packetCounter.incrementAndGet();
            
            JSObject packetInfo = new JSObject();
            packetInfo.put("packetNumber", pktNum);
            packetInfo.put("source", sourceIp + ":" + sourcePort);
            packetInfo.put("destination", destIp + ":" + destPort);
            packetInfo.put("protocol", protocol == 6 ? "TCP" : "UDP");
            packetInfo.put("direction", direction);
            packetInfo.put("size", length);
            packetInfo.put("appName", appName);
            packetInfo.put("packageName", packageName);
            packetInfo.put("uid", uid);
            
            StringBuilder payload = new StringBuilder();
            int payloadLength = Math.min(length, 64);
            for (int i = 0; i < payloadLength; i++) {
                payload.append(String.format("%02X ", data[i]));
                if ((i + 1) % 16 == 0) payload.append("\n");
            }
            packetInfo.put("payload", payload.toString().trim());
            
            ToyVpnPlugin.notifyPacketCaptured(packetInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error notifying packet", e);
        }
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
    
    public void stopVpnGracefully() {
        if (shuttingDown.getAndSet(true)) return;
        
        Log.d(TAG, "Stopping VPN");
        running.set(false);
        
        new Thread(() -> {
            try {
                if (vpnThread != null) vpnThread.interrupt();
                if (writeThread != null) writeThread.interrupt();
                
                if (vpnThread != null) vpnThread.join(1000);
                if (writeThread != null) writeThread.join(1000);
                
                if (selector != null && selector.isOpen()) selector.close();
                
                for (TcpConnection conn : tcpConnections.values()) {
                    closeTcpConnection(conn);
                }
                tcpConnections.clear();
                
                for (UdpConnection conn : udpConnections.values()) {
                    closeUdpConnection(conn);
                }
                udpConnections.clear();
                
                if (vpnInterface != null) {
                    try { vpnInterface.close(); } catch (IOException ignored) {}
                    vpnInterface = null;
                }
                
                if (writeQueue != null) writeQueue.clear();
                
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