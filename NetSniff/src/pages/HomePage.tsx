"use client"

import type React from "react"
import { useState, useEffect, useRef, useCallback } from "react"
import {
  IonPage,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonButton,
  IonGrid,
  IonRow,
  IonCol,
  IonSpinner,
  IonList,
  IonItem,
  IonLabel,
} from "@ionic/react"
import { useHistory } from "react-router-dom"
import { usePackets } from "../context/PacketContext"
import "./HomePage.css" // Import the CSS file

const HomePage: React.FC = () => {
  const { packets, clearPackets, isCapturing, startCapture, stopCapture } = usePackets()
  const [notification, setNotification] = useState<{message: string; type: 'success' | 'error' | 'info'}|null>(null)
  const history = useHistory()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const contentRef = useRef<HTMLIonContentElement>(null)

  useEffect(() => {
    console.log("HomePage: Component mounted");
    return () => {
      console.log("HomePage: Component unmounting");
    }
  }, [])

  // Show notification function
  const showNotification = useCallback((message: string, type: 'success' | 'error' | 'info' = 'info') => {
    setNotification({ message, type });
    // Auto-hide notification after 4 seconds
    setTimeout(() => {
      setNotification(null);
    }, 4000);
  }, []);

  const handleStartCapture = async () => {
    console.log("HomePage: Start capture button clicked");
    setLoading(true)
    setError(null)
    
    try {
      console.log("HomePage: Attempting to start packet capture");
      await startCapture();
      console.log("HomePage: Packet capture started successfully");
      showNotification("Packet capture started successfully", "success");
    } catch (error) {
      console.error("HomePage: Error starting packet capture:", error);
      const errorMessage = error instanceof Error ? 
        `${error.name}: ${error.message}\n${error.stack}` : 
        String(error);
      console.error("HomePage: Detailed error:", errorMessage);
      setError("Failed to start packet capture. Please check logs for details.");
    } finally {
      setLoading(false)
    }
  }

  const handleStopCapture = async () => {
    console.log("HomePage: Stop capture button clicked");
    setLoading(true)
    try {
      console.log("HomePage: Attempting to stop packet capture");
      await stopCapture();
      console.log("HomePage: Packet capture stopped successfully");
      showNotification("Packet capture stopped", "info");
    } catch (error) {
      console.error("HomePage: Error stopping packet capture:", error);
      const errorMessage = error instanceof Error ? 
        `${error.name}: ${error.message}\n${error.stack}` : 
        String(error);
      console.error("HomePage: Detailed error:", errorMessage);
      setError("Failed to stop packet capture. Please check logs for details.");
    } finally {
      setLoading(false)
    }
  }

  const handleClearPackets = () => {
    console.log("HomePage: Clear packets button clicked");
    clearPackets();
    showNotification("All packets cleared", "info");
  }

  const handlePacketClick = (packet: any) => {
    console.log("HomePage: Packet clicked:", packet);
    history.push(`/packet/${packet.id}`)
  }

  // Function to determine row color based on protocol or index
  const getRowColor = (index: number, protocol: string) => {
    if (protocol === "TCP" || protocol === "HTTP") {
      return "var(--ion-color-success-shade)" // Green
    } else if (protocol === "UDP" || protocol === "DNS") {
      return "var(--ion-color-primary-shade)" // Blue
    } else {
      return "var(--ion-color-danger-shade)" // Red
    }
  }

  // Scroll to bottom when new packets arrive
  useEffect(() => {
    if (isCapturing && packets.length > 0 && contentRef.current) {
      contentRef.current.scrollToBottom(300)
    }
  }, [packets.length, isCapturing])

  // Render a packet row (for virtual scroll)
  const renderPacketItem = (packet: any, index: number) => {
    try {
      // Safety check for packet
      if (!packet || !packet.id) {
        console.error("Invalid packet data:", packet);
        return null;
      }
      
      return (
        <IonItem
          key={packet.id}
          button
          onClick={() => handlePacketClick(packet)}
          className="packet-item dark:border-gray-700 mb-1.5"
          style={{ backgroundColor: getRowColor(index, packet.protocol) }}
        >
          <IonLabel>
            <h2 className="font-bold text-white text-lg mb-1">Packet {packet.number}</h2>
            <p className="text-white text-sm mb-1"><span className="font-semibold text-gray-100">Source:</span> {packet.source}</p>
            <p className="text-white text-sm mb-1"><span className="font-semibold text-gray-100">Destination:</span> {packet.destination}</p>
            <p className="text-white text-sm"><span className="font-semibold text-gray-100">Protocol:</span> {packet.protocol}</p>
          </IonLabel>
        </IonItem>
      )
    } catch (error) {
      console.error("Error rendering packet item:", error);
      return null;
    }
  }

  return (
    <IonPage className="bg-white dark:bg-gray-900">
      {/* Notification Component */}
      {notification && (
        <div className={`notification-card ${notification.type === 'success' ? 'bg-green-500' : notification.type === 'error' ? 'bg-red-500' : 'bg-blue-500'} text-white p-3 shadow-lg`}>
          <div className="flex items-center justify-between">
            <p className="font-medium text-md">{notification.message}</p>
            <button onClick={() => setNotification(null)} className="text-white hover:text-gray-200">
              <span className="text-xl">×</span>
            </button>
          </div>
        </div>
      )}
      <IonHeader>
        <IonToolbar color="primary" className="dark:bg-gray-800">
          <IonTitle className="font-bold text-white text-center">NetSniff</IonTitle>
        </IonToolbar>
      </IonHeader>
      <IonContent className="ion-padding bg-white dark:bg-gray-900" ref={contentRef}>
        {error && (
          <div className="p-4 bg-red-100 dark:bg-red-800 text-red-800 dark:text-white rounded-lg mb-4">
            <p className="font-medium">{error}</p>
          </div>
        )}
        
        <IonButton expand="full" onClick={isCapturing ? handleStopCapture : handleStartCapture} disabled={loading} className="mb-3 main-button">
          <span className="font-medium">{isCapturing ? "Stop Capture" : "Start Capture"}</span>
          {loading && <IonSpinner name="crescent" />}
        </IonButton>

        <IonButton expand="full" color="secondary" onClick={handleClearPackets} disabled={packets.length === 0} className="mb-4 clear-button">
          <span className="font-medium">Clear Packets</span>
        </IonButton>

        <IonGrid className="packet-table bg-white dark:bg-gray-800 rounded-lg overflow-hidden shadow-md">
          <IonRow className="packet-header bg-blue-600 dark:bg-blue-800 text-white font-semibold text-sm uppercase text-center">
            <IonCol size="1" className="py-3">No.</IonCol>
            <IonCol size="3" className="py-3">Source</IonCol>
            <IonCol size="3" className="py-3">Destination</IonCol>
            <IonCol size="2" className="py-3">Protocol</IonCol>
            <IonCol size="3" className="py-3">Info</IonCol>
          </IonRow>

          {/* Use a virtualized list for better performance with large datasets */}
          {Array.isArray(packets) && packets.length > 0 ? (
            <IonList className="packet-list">
              {packets.map((packet, index) => renderPacketItem(packet, index))}
            </IonList>
          ) : (
            <div className="empty-state dark:bg-gray-800 p-8 rounded-b-lg">
              {!isCapturing && (
                <p className="empty-message text-gray-700 dark:text-gray-200 font-medium text-center">No packets captured yet. <span className="text-blue-600 dark:text-blue-400 font-bold">Start capturing</span> to see network traffic.</p>
              )}
              {isCapturing && <p className="empty-message text-gray-700 dark:text-gray-200 font-medium text-center">Capturing Packets... <span className="text-blue-600 dark:text-blue-400 animate-pulse font-bold">Please wait</span></p>}
            </div>
          )}
        </IonGrid>
      </IonContent>
    </IonPage>
  )
}

export default HomePage