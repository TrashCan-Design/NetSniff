
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
import android.os.PowerManager;
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
    private static final long DNS_REFRESH_INTERVAL_MS = 300000;
    private static final int MTU = 1500; // Standard MTU
    
    private static final int NOTIFICATION_ID = 1234;
    private static final String CHANNEL_ID = "NetSniffVpnChannel";
    
    public static final String ACTION_CONNECT = "com.netsniff.app.START";
    public static final String ACTION_DISCONNECT = "com.netsniff.app.STOP";
    
    public static final int TCP_IDLE = 0;
    public static final int TCP_SYN_SENT = 1;
    public static final int TCP_SYN_RECEIVED = 2;
    public static final int TCP_ESTABLISHED = 3;
    public static final int TCP_FIN_WAIT = 4;
    public static final int TCP_CLOSE_WAIT = 5;
    public static final int TCP_CLOSING = 6;
    public static final int TCP_CLOSED = 7;
    
    private ParcelFileDescriptor vpnInterface = null;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    private Thread vpnThread;
    private Thread writeThread;
    private Thread dnsResolverThread;
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
    
    private PacketAggregator packetAggregator;
    
    private Set<String> blockedDomains;
    private Set<String> blockedIps;
    private ConcurrentHashMap<String, Set<String>> domainToIpsMap;
    private final Object blockingLock = new Object();
    
    private PowerManager.WakeLock wakeLock;

    public static class TcpConnection {
        SocketChannel channel;
        String key;
        String sourceIp;
        int sourcePort;
        String destIp;
        int destPort;
        long lastActivity;
        int uid;
        
        int state;
        long localSeq;
        long remoteSeq;
        long localSeqStart;
        long remoteSeqStart;
        long remoteAck;  // tracking remote ACK 
        int sendWindow;
        int recvWindow;
        int mss;
        boolean windowScaleSupported;
        int recvScale;
        int sendScale;
        
        ByteBuffer readBuffer;
        LinkedList<Segment> forwardQueue;
        ByteBuffer pendingWrite;

        TcpConnection(String key, String sourceIp, int sourcePort, String destIp, int destPort, int uid) {
            this.key = key;
            this.sourceIp = sourceIp;
            this.sourcePort = sourcePort;
            this.destIp = destIp;
            this.destPort = destPort;
            this.uid = uid;
            this.lastActivity = System.currentTimeMillis();
            this.readBuffer = ByteBuffer.allocate(65536);
            this.forwardQueue = new LinkedList<>();
            this.state = TCP_IDLE;
            this.localSeq = (long) (Math.random() * 0xFFFFFFFFL);
            this.localSeqStart = this.localSeq;
            this.remoteSeq = 0;
            this.remoteSeqStart = 0;
            this.remoteAck = 0;
            this.sendWindow = 65535;
            this.recvWindow = 65535;
            this.mss = 1460;
            this.windowScaleSupported = false;
            this.recvScale = 0;
            this.sendScale = 0;
        }
    }
    
    public static class Segment {
        long seq;
        byte[] data;
        boolean psh;
        
        Segment(long seq, byte[] data, boolean psh) {
            this.seq = seq;
            this.data = data;
            this.psh = psh;
        }
    }
    
    public static class UdpConnection {
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
        Allowed.initialize(getApplicationContext());
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
        
        packetAggregator = new PacketAggregator();
        
        blockedDomains = ConcurrentHashMap.newKeySet();
        blockedIps = ConcurrentHashMap.newKeySet();
        domainToIpsMap = new ConcurrentHashMap<>();
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetSniff::VpnWakeLock");
        
        Allowed.loadBlacklist(getApplicationContext());
        
        buildUidCache();
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
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
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
            .setContentTitle("NetSniff VPN Active")
            .setContentText("Monitoring with domain blocking")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            
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
        
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        
        establishVpn();
        
        return START_STICKY;
    }

    private void establishVpn() {
        try {
            Builder builder = new Builder()
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .setSession("NetSniff-VPN")
                .setMtu(MTU)
                .setBlocking(false);

            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }

            selector = Selector.open();
            running.set(true);
            
            resolveDomains();
            
            vpnThread = new Thread(new VPNRunnable(), "VPN-Thread");
            vpnThread.start();
            
            writeThread = new Thread(new WriteRunnable(), "Write-Thread");
            writeThread.start();
            
            dnsResolverThread = new Thread(new DnsResolverRunnable(), "DNS-Resolver");
            dnsResolverThread.start();
            
            new Thread(new UsageStatsRunnable(), "UsageStats-Tracker").start();
            
            Log.d(TAG, "VPN established with domain blocking");
            
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            stopVpnGracefully();
        }
    }
    
    private void resolveDomains() {
        Set<String> domainsToResolve = Allowed.getBlockedEntries();
        if (domainsToResolve.isEmpty()) return;
        
        Log.d(TAG, "Starting DNS resolution for " + domainsToResolve.size() + " blocked domains");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(domainsToResolve.size());
        
        for (String domain : domainsToResolve) {
            executor.submit(() -> {
                try {
                    resolveDomain(domain);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "DNS resolution interrupted", e);
        }
        
        executor.shutdown();
        
        synchronized (blockingLock) {
            Log.d(TAG, "DNS resolution complete: " + blockedIps.size() + " IPs");
        }
    }
    
    private void resolveDomain(String domain) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(domain);
            Set<String> ips = new HashSet<>();
            
            for (InetAddress addr : addresses) {
                String ip = addr.getHostAddress();
                if (ip != null && !ip.contains(":")) {
                    ips.add(ip);
                    synchronized (blockingLock) {
                        blockedIps.add(ip);
                    }
                }
            }
            
            if (!ips.isEmpty()) {
                domainToIpsMap.put(domain, ips);
                Log.d(TAG, "Resolved " + domain + " to " + ips.size() + " IPs");
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve domain: " + domain);
        }
    }
    
    private class DnsResolverRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "DNS resolver thread started");
            
            while (running.get() && !Thread.interrupted()) {
                try {
                    Thread.sleep(DNS_REFRESH_INTERVAL_MS);
                    
                    if (!running.get()) break;
                    
                    Log.d(TAG, "Refreshing DNS resolutions");
                    Allowed.loadBlacklist(getApplicationContext());
                    resolveDomains();
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in DNS resolver thread", e);
                }
            }
            
            Log.d(TAG, "DNS resolver thread stopped");
        }
    }
    
    private boolean isBlockedIp(String ip) {
        if (Allowed.isDomainBlacklisted(ip)) return true;
        
        synchronized (blockingLock) {
            return blockedIps.contains(ip);
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
                    
                    processSocketEvents();
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
            
            if (version != 4) return;
            
            int ihl = (versionAndIHL & 0xF) * 4;
            int protocol = buffer.get(9) & 0xFF;
            
            byte[] sourceAddr = new byte[4];
            byte[] destAddr = new byte[4];
            buffer.position(12);
            buffer.get(sourceAddr);
            buffer.get(destAddr);
            
            String sourceIp = ipToString(sourceAddr);
            String destIp = ipToString(destAddr);
            
            if (protocol == 6) {
                handleTcpPacket(buffer, ihl, sourceIp, destIp, length);
            } else if (protocol == 17) {
                handleUdpPacket(buffer, ihl, sourceIp, destIp, length);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling packet", e);
        }
    }
    
    private void handleTcpPacket(ByteBuffer buffer, int ihl, String sourceIp, String destIp, int totalLength) {
        try {
            if (isBlockedIp(destIp)) {
                Log.d(TAG, "Blocked TCP to: " + destIp);
                return;
            }
            
            buffer.position(ihl);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            long seq = buffer.getInt() & 0xFFFFFFFFL;
            long ackSeq = buffer.getInt() & 0xFFFFFFFFL;
            
            int dataOffsetAndFlags = buffer.getShort() & 0xFFFF;
            int tcpHeaderLen = ((dataOffsetAndFlags >> 12) & 0xF) * 4;
            
            byte flags = buffer.get(ihl + 13);
            boolean syn = (flags & 0x02) != 0;
            boolean ack = (flags & 0x10) != 0;
            boolean fin = (flags & 0x01) != 0;
            boolean rst = (flags & 0x04) != 0;
            boolean psh = (flags & 0x08) != 0;
            
            int window = buffer.getShort(ihl + 14) & 0xFFFF;
            
            int headerSize = ihl + tcpHeaderLen;
            int dataSize = totalLength - headerSize;
            
            String key = sourceIp + ":" + sourcePort + "-" + destIp + ":" + destPort;
            TcpConnection conn = tcpConnections.get(key);

            if (rst) {
                if (conn != null) {
                    closeTcpConnection(conn);
                    tcpConnections.remove(key);
                }
                return;
            }
            
            if (syn && !ack) {
                int uid = getMostLikelyActiveUid();
                
                int mss = 1460;
                int ws = 0;
                boolean wsSupported = false;
                int optLen = tcpHeaderLen - 20;
                int optPos = ihl + 20;
                
                while (optLen > 0 && optPos < buffer.limit()) {
                    int kind = buffer.get(optPos) & 0xFF;
                    if (kind == 0) break;
                    if (kind == 1) {
                        optLen--;
                        optPos++;
                        continue;
                    }
                    
                    int len = buffer.get(optPos + 1) & 0xFF;
                    if (kind == 2 && len == 4) {
                        mss = buffer.getShort(optPos + 2) & 0xFFFF;
                    } else if (kind == 3 && len == 3) {
                        ws = buffer.get(optPos + 2) & 0xFF;
                        wsSupported = true;
                    }
                    
                    optLen -= len;
                    optPos += len;
                }
                
                conn = new TcpConnection(key, sourceIp, sourcePort, destIp, destPort, uid);
                conn.remoteSeq = seq + 1;  // SYN consumes 1 sequence number
                conn.remoteSeqStart = seq;
                conn.sendWindow = window;
                conn.mss = Math.min(mss, MTU - 40);  // Account for IP + TCP headers
                conn.windowScaleSupported = wsSupported;
                conn.sendScale = wsSupported ? ws : 0;
                conn.recvScale = wsSupported ? 7 : 0;  // cale factor
                
                try {
                    conn.channel = SocketChannel.open();
                    conn.channel.configureBlocking(false);
                    conn.channel.socket().setTcpNoDelay(true);
                    conn.channel.socket().setSendBufferSize(65536);
                    conn.channel.socket().setReceiveBufferSize(65536);
                    protect(conn.channel.socket());
                    
                    InetSocketAddress remote = new InetSocketAddress(destIp, destPort);
                    conn.channel.connect(remote);
                    
                    conn.state = TCP_SYN_SENT;
                    
                    conn.channel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ, conn);
                    tcpConnections.put(key, conn);
                    
                    Log.d(TAG, "New TCP connection: " + key + " MSS=" + conn.mss);
                    
                    notifyPacketOptimized(buffer.array(), totalLength, "outgoing", uid, 
                                        sourceIp, sourcePort, destIp, destPort, 6);
                    
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create TCP socket", e);
                    sendTcpReset(sourceIp, sourcePort, destIp, destPort, 0, seq + 1);
                }
                return;
            }
            
            if (conn == null) {
                Log.w(TAG, "No connection for packet: " + key);
                if (!syn) {
                    sendTcpReset(sourceIp, sourcePort, destIp, destPort, ackSeq, seq + dataSize + (fin ? 1 : 0));
                }
                return;
            }
            
            conn.lastActivity = System.currentTimeMillis();
            
            // Update window
            if (conn.windowScaleSupported) {
                conn.sendWindow = window << conn.sendScale;
            } else {
                conn.sendWindow = window;
            }
            
            // Handle ACK
            if (ack) {
                conn.remoteAck = ackSeq;
                
                if (conn.state == TCP_SYN_RECEIVED && ackSeq == conn.localSeq) {
                    conn.state = TCP_ESTABLISHED;
                    Log.d(TAG, "TCP established: " + key);
                    
                    // forward any queued data
                    if (!conn.forwardQueue.isEmpty()) {
                        try {
                            SelectionKey selKey = conn.channel.keyFor(selector);
                            if (selKey != null && selKey.isValid()) {
                                selKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating interest ops", e);
                        }
                    }
                }
            }
            
            // Handle data
            if (dataSize > 0) {
                if (conn.state == TCP_ESTABLISHED || conn.state == TCP_CLOSE_WAIT) {
                    // Check if this is the expected sequence number
                    if (seq == conn.remoteSeq) {
                        // In order segment
                        byte[] data = new byte[dataSize];
                        buffer.position(headerSize);
                        buffer.get(data);
                        
                        Segment segment = new Segment(seq, data, psh);
                        synchronized (conn.forwardQueue) {
                            conn.forwardQueue.add(segment);
                        }
                        
                        conn.remoteSeq += dataSize;
                        
                        // Send ACK
                        sendTcpAck(conn);
                        
                        // Enable writing
                        try {
                            SelectionKey selKey = conn.channel.keyFor(selector);
                            if (selKey != null && selKey.isValid()) {
                                selKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error enabling write", e);
                        }
                        
                    } else if (seq > conn.remoteSeq) {
                        // if Out-of-order segment - queue it
                        byte[] data = new byte[dataSize];
                        buffer.position(headerSize);
                        buffer.get(data);
                        
                        Segment segment = new Segment(seq, data, psh);
                        synchronized (conn.forwardQueue) {
                            // Ensure Insert in order only
                            boolean inserted = false;
                            for (int i = 0; i < conn.forwardQueue.size(); i++) {
                                if (conn.forwardQueue.get(i).seq > seq) {
                                    conn.forwardQueue.add(i, segment);
                                    inserted = true;
                                    break;
                                }
                            }
                            if (!inserted) {
                                conn.forwardQueue.add(segment);
                            }
                        }
                        
                        // Send duplicate ACK
                        sendTcpAck(conn);
                        
                    } else {
                        // Old segment, just ACK it
                        sendTcpAck(conn);
                    }
                }
            }
            
            // Handle FIN
            if (fin) {
                if (conn.state == TCP_ESTABLISHED) {
                    conn.state = TCP_CLOSE_WAIT;
                    conn.remoteSeq++;  // FIN consumes 1 sequence number
                    sendTcpAck(conn);
                    
                    // Try to close our side too
                    try {
                        conn.channel.shutdownInput();
                    } catch (IOException e) {
                        Log.e(TAG, "Error shutting down input", e);
                    }
                    
                    Log.d(TAG, "TCP FIN received: " + key);
                } else if (conn.state == TCP_FIN_WAIT) {
                    conn.remoteSeq++;
                    sendTcpAck(conn);
                    closeTcpConnection(conn);
                    tcpConnections.remove(key);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling TCP packet", e);
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
                        
                        try {
                            if (key.isValid() && key.isConnectable()) {
                                handleTcpConnect(conn, key);
                            }
                            if (key.isValid() && key.isReadable()) {
                                handleTcpRead(conn);
                            }
                            if (key.isValid() && key.isWritable()) {
                                handleTcpWrite(conn, key);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing TCP key: " + conn.key, e);
                            closeTcpConnection(conn);
                            tcpConnections.remove(conn.key);
                        }
                        
                    } else if (attachment instanceof UdpConnection) {
                        UdpConnection conn = (UdpConnection) attachment;
                        
                        try {
                            if (key.isValid() && key.isReadable()) {
                                handleUdpRead(conn);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing UDP key: " + conn.key, e);
                            closeUdpConnection(conn);
                            udpConnections.remove(conn.key);
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
                
                conn.state = TCP_SYN_RECEIVED;
                
                // Send SYN-ACK
                sendTcpSynAck(conn);
                conn.localSeq++;
                
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to complete connection: " + conn.key, e);
            sendTcpReset(conn.sourceIp, conn.sourcePort, conn.destIp, conn.destPort, 
                        conn.localSeq, conn.remoteSeq);
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
                sendTcpData(conn, data, data.length);
                
                notifyPacketOptimized(data, data.length, "incoming", conn.uid, 
                    conn.destIp, conn.destPort, conn.sourceIp, conn.sourcePort, 6);
                    
            } else if (bytesRead < 0) {
                Log.d(TAG, "TCP remote closed: " + conn.key);
                
                if (conn.state == TCP_ESTABLISHED) {
                    conn.state = TCP_FIN_WAIT;
                    sendTcpFinAck(conn);
                } else {
                    closeTcpConnection(conn);
                    tcpConnections.remove(conn.key);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading from TCP socket: " + conn.key, e);
            closeTcpConnection(conn);
            tcpConnections.remove(conn.key);
        }
    }
    
    private void handleTcpWrite(TcpConnection conn, SelectionKey key) {
        try {
            synchronized (conn.forwardQueue) {
                while (!conn.forwardQueue.isEmpty()) {
                    Segment segment = conn.forwardQueue.peek();
                    
                    // Only process if this is the next expected segment
                    if (segment == null) break;
                    
                    // Check if we have window space
                    if (conn.sendWindow <= 0) {
                        Log.d(TAG, "Send window full for " + conn.key);
                        break;
                    }
                    
                    ByteBuffer buf = ByteBuffer.wrap(segment.data);
                    int written = conn.channel.write(buf);
                    
                    if (written > 0) {
                        if (written == segment.data.length) {
                            // Entire segment written
                            conn.forwardQueue.poll();
                        } else {
                            // Partial write - update segment
                            byte[] remaining = new byte[segment.data.length - written];
                            System.arraycopy(segment.data, written, remaining, 0, remaining.length);
                            segment.data = remaining;
                            break;
                        }
                    } else {
                        // Can't write anymore
                        break;
                    }
                }
                
                // Update interest ops
                if (conn.forwardQueue.isEmpty()) {
                    key.interestOps(SelectionKey.OP_READ);
                } else {
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                }
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error writing to TCP socket: " + conn.key, e);
            closeTcpConnection(conn);
            tcpConnections.remove(conn.key);
        }
    }
    
    private void sendTcpSynAck(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, true, true, false, false);
        if (packet != null) {
            writeQueue.offer(packet);
        }
    }
    
    private void sendTcpAck(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, false, true, false, false);
        if (packet != null) {
            writeQueue.offer(packet);
        }
    }
    
    private void sendTcpFinAck(TcpConnection conn) {
        ByteBuffer packet = buildTcpPacket(conn, null, 0, false, true, true, false);
        if (packet != null) {
            writeQueue.offer(packet);
            conn.localSeq++;
        }
    }
    
    private void sendTcpReset(String sourceIp, int sourcePort, String destIp, int destPort, long seq, long ack) {
        try {
            int totalSize = 20 + 20;
            ByteBuffer packet = ByteBuffer.allocate(totalSize);
            
            // IP header
            packet.put((byte) 0x45);
            packet.put((byte) 0x00);
            packet.putShort((short) totalSize);
            packet.putShort((short) 0);
            packet.putShort((short) 0x4000);
            packet.put((byte) 64);
            packet.put((byte) 6);
            packet.putShort((short) 0);
            
            packet.put(ipToBytes(destIp));
            packet.put(ipToBytes(sourceIp));
            
            packet.putShort(10, PacketUtils.calculateIPChecksum(packet, 0));
            
            // TCP header
            int tcpStart = packet.position();
            packet.putShort((short) destPort);
            packet.putShort((short) sourcePort);
            packet.putInt((int) seq);
            packet.putInt((int) ack);
            packet.putShort((short) 0x5014);  // RST + ACK
            packet.putShort((short) 0);
            packet.putShort((short) 0);
            packet.putShort((short) 0);
            
            packet.putShort(tcpStart + 16, PacketUtils.calculateTCPChecksum(packet, 0, tcpStart, 20));
            
            packet.flip();
            writeQueue.offer(packet);
        } catch (Exception e) {
            Log.e(TAG, "Error sending RST", e);
        }
    }
    
    private void sendTcpData(TcpConnection conn, byte[] data, int length) {
        // Split into MSS-sized chunks if necessary
        int offset = 0;
        while (offset < length) {
            int chunkSize = Math.min(conn.mss, length - offset);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(data, offset, chunk, 0, chunkSize);
            
            ByteBuffer packet = buildTcpPacket(conn, chunk, chunkSize, false, true, false, false);
            if (packet != null) {
                writeQueue.offer(packet);
                conn.localSeq += chunkSize;
            }
            
            offset += chunkSize;
        }
    }
    
    private ByteBuffer buildTcpPacket(TcpConnection conn, byte[] payload, int payloadSize,
                                     boolean syn, boolean ack, boolean fin, boolean rst) {
        
        int optLen = syn ? 8 : 0;
        int totalSize = 20 + 20 + optLen + payloadSize;
        
        if (totalSize > MTU) {
            Log.w(TAG, "Packet too large: " + totalSize + " > " + MTU);
            return null;
        }
        
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // IP header
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
        
        // TCP header
        int tcpStart = packet.position();
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putInt((int) conn.localSeq);
        packet.putInt((int) conn.remoteSeq);
        
        int dataOffset = (20 + optLen) / 4;
        int flags = (dataOffset << 12);
        if (syn) flags |= 0x0002;
        if (ack) flags |= 0x0010;
        if (fin) flags |= 0x0001;
        if (rst) flags |= 0x0004;
        if (payloadSize > 0) flags |= 0x0008;  
        packet.putShort((short) flags);
        
        // Calculate window size with scaling
        int window = conn.recvWindow;
        if (conn.windowScaleSupported) {
            window = window >> conn.recvScale;
        }
        packet.putShort((short) Math.min(window, 65535));
        packet.putShort((short) 0);  // Checksum (filled later)
        packet.putShort((short) 0);  // Urgent pointer
        
        // TCP options
        if (syn) {
            // MSS option
            packet.put((byte) 2);
            packet.put((byte) 4);
            packet.putShort((short) conn.mss);
            
            // Window scale option
            packet.put((byte) 3);
            packet.put((byte) 3);
            packet.put((byte) conn.recvScale);
            packet.put((byte) 1);  // NOP for alignment
        }
        
        // Payload
        if (payload != null && payloadSize > 0) {
            packet.put(payload, 0, payloadSize);
        }
        
        // Calculate TCP checksum
        int tcpChecksumPos = tcpStart + 16;
        packet.putShort(tcpChecksumPos, 
            PacketUtils.calculateTCPChecksum(packet, 0, tcpStart, 20 + optLen + payloadSize));
        
        packet.flip();
        return packet;
    }
    
    private void handleUdpPacket(ByteBuffer buffer, int ihl, String sourceIp, String destIp, int totalLength) {
        try {
            if (isBlockedIp(destIp)) {
                Log.d(TAG, "Blocked UDP to: " + destIp);
                return;
            }
            
            buffer.position(ihl);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            int length = buffer.getShort() & 0xFFFF;
            
            String key = sourceIp + ":" + sourcePort + "-" + destIp + ":" + destPort;
            
            UdpConnection conn = udpConnections.get(key);
            if (conn == null) {
                int uid = getMostLikelyActiveUid();
                conn = new UdpConnection(key, sourceIp, sourcePort, destIp, destPort, uid);
                
                try {
                    conn.channel = DatagramChannel.open();
                    conn.channel.configureBlocking(false);
                    conn.channel.socket().setSendBufferSize(65536);
                    conn.channel.socket().setReceiveBufferSize(65536);
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
            
            int dataSize = length - 8;
            if (dataSize > 0) {
                buffer.position(ihl + 8);
                byte[] data = new byte[dataSize];
                buffer.get(data);
                
                InetSocketAddress dest = new InetSocketAddress(destIp, destPort);
                conn.channel.send(ByteBuffer.wrap(data), dest);
                
                notifyPacketOptimized(buffer.array(), totalLength, "outgoing", conn.uid, 
                    sourceIp, sourcePort, destIp, destPort, 17);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling UDP packet", e);
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
                
                ByteBuffer response = buildUdpPacket(conn, data, data.length);
                if (response != null) {
                    writeQueue.offer(response);
                }
                
                notifyPacketOptimized(data, data.length, "incoming", conn.uid,
                    conn.destIp, conn.destPort, conn.sourceIp, conn.sourcePort, 17);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading from UDP socket", e);
            closeUdpConnection(conn);
            udpConnections.remove(conn.key);
        }
    }
    
    private ByteBuffer buildUdpPacket(UdpConnection conn, byte[] payload, int payloadSize) {
        int totalSize = 20 + 8 + payloadSize;
        
        if (totalSize > MTU) {
            Log.w(TAG, "UDP packet too large: " + totalSize);
            return null;
        }
        
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // IP header
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
        
        // UDP header
        int udpStart = packet.position();
        packet.putShort((short) conn.destPort);
        packet.putShort((short) conn.sourcePort);
        packet.putShort((short) (8 + payloadSize));
        packet.putShort((short) 0);
        
        // Payload
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
            if (conn.channel != null) {
                SelectionKey key = conn.channel.keyFor(selector);
                if (key != null) {
                    key.cancel();
                }
                conn.channel.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing TCP connection", e);
        }
    }
    
    private void closeUdpConnection(UdpConnection conn) {
        try {
            if (conn.channel != null) {
                SelectionKey key = conn.channel.keyFor(selector);
                if (key != null) {
                    key.cancel();
                }
                conn.channel.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing UDP connection", e);
        }
    }
    
    private void notifyPacketOptimized(byte[] data, int length, String direction, int uid,
                                      String sourceIp, int sourcePort, String destIp, int destPort, int protocol) {
        try {
            String appName = getAppNameForUid(uid);
            String packageName = getPackageNameForUid(uid);
            long pktNum = packetCounter.incrementAndGet();
            String protocolName = protocol == 6 ? "TCP" : "UDP";
            long timestamp = System.currentTimeMillis();
            
            Allowed.storeTraffic(
                sourceIp, sourcePort, destIp, destPort,
                protocolName, direction, length, appName,
                packageName, uid, null, ""
            );
            
            JSObject packetInfo = new JSObject();
            packetInfo.put("packetNumber", pktNum);
            packetInfo.put("source", sourceIp + ":" + sourcePort);
            packetInfo.put("destination", destIp + ":" + destPort);
            packetInfo.put("protocol", protocolName);
            packetInfo.put("direction", direction);
            packetInfo.put("size", length);
            packetInfo.put("appName", appName);
            packetInfo.put("packageName", packageName);
            packetInfo.put("uid", uid);
            packetInfo.put("timestamp", timestamp);
            
            StringBuilder payload = new StringBuilder();
            int payloadLength = Math.min(length, 32);
            for (int i = 0; i < payloadLength; i++) {
                payload.append(String.format("%02X ", data[i]));
                if ((i + 1) % 16 == 0) payload.append("\n");
            }
            packetInfo.put("payload", payload.toString().trim());
            
            packetAggregator.queuePacket(packetInfo);
            
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
                if (packetAggregator != null) {
                    packetAggregator.stop();
                }
                
                if (vpnThread != null) vpnThread.interrupt();
                if (writeThread != null) writeThread.interrupt();
                if (dnsResolverThread != null) dnsResolverThread.interrupt();
                
                if (vpnThread != null) vpnThread.join(1000);
                if (writeThread != null) writeThread.join(1000);
                if (dnsResolverThread != null) dnsResolverThread.join(1000);
                
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
                
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                
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