package com.netsniff.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import org.json.JSONException;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregates packets and sends them in batches to prevent UI lag
 * Instead of sending 1000 individual notifications, sends them in batches of 50
 */
public class PacketAggregator {
    private static final String TAG = "PacketAggregator";
    private static final int BATCH_SIZE = 50;
    private static final int BATCH_DELAY_MS = 100;  // Send batch every 100ms
    
    private final ConcurrentLinkedQueue<JSObject> packetQueue;
    private final Handler batchHandler;
    private final AtomicInteger queuedCount;
    private volatile boolean running;
    
    public PacketAggregator() {
        this.packetQueue = new ConcurrentLinkedQueue<>();
        this.batchHandler = new Handler(Looper.getMainLooper());
        this.queuedCount = new AtomicInteger(0);
        this.running = true;
        
        // Start batch processing
        scheduleBatchSend();
    }
    
    /**
     * Queue a packet for batched notification
     */
    public void queuePacket(JSObject packet) {
        if (!running) return;
        
        packetQueue.offer(packet);
        int count = queuedCount.incrementAndGet();
        
        // If queue is getting large, send immediately
        if (count >= BATCH_SIZE * 2) {
            sendBatch();
        }
    }
    
    /**
     * Schedule next batch send
     */
    private void scheduleBatchSend() {
        if (!running) return;
        
        batchHandler.postDelayed(() -> {
            sendBatch();
            scheduleBatchSend();  // Schedule next batch
        }, BATCH_DELAY_MS);
    }
    
    /**
     * Send accumulated packets as a batch
     */
    private void sendBatch() {
        int count = queuedCount.get();
        if (count == 0) return;
        
        JSArray batch = new JSArray();
        int sent = 0;
        
        // Collect up to BATCH_SIZE packets
        for (int i = 0; i < BATCH_SIZE && !packetQueue.isEmpty(); i++) {
            JSObject packet = packetQueue.poll();
            if (packet != null) {
                try {
                    batch.put(packet);
                    sent++;
                } 
                catch (Exception e) {
                    Log.e(TAG, "Error adding packet to batch", e);
                }
            }
        }
        
        if (sent > 0) {
            // Send batch notification
            JSObject batchData = new JSObject();
            batchData.put("packets", batch);
            batchData.put("count", sent);
            batchData.put("remaining", queuedCount.addAndGet(-sent));
            
            ToyVpnPlugin.notifyPacketBatch(batchData);
            
            Log.d(TAG, "Sent packet batch: " + sent + " packets, " + 
                      queuedCount.get() + " remaining");
        }
    }
    
    /**
     * Stop the aggregator
     */
    public void stop() {
        running = false;
        batchHandler.removeCallbacksAndMessages(null);
        
        // Send any remaining packets
        sendBatch();
        
        packetQueue.clear();
        queuedCount.set(0);
    }
    
    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return queuedCount.get();
    }
    
    /**
     * Clear all queued packets
     */
    public void clear() {
        packetQueue.clear();
        queuedCount.set(0);
    }
}