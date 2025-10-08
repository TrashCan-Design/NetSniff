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
  IonSpinner,
  IonList,
  IonItem,
  IonLabel,
  IonSearchbar,
  IonSegment,
  IonSegmentButton,
  IonInfiniteScroll,
  IonInfiniteScrollContent,
  IonBadge,
  IonCard,
  IonCardContent,
  IonGrid,
  IonRow,
  IonCol,
} from "@ionic/react"
import { useHistory } from "react-router-dom"
import { usePackets } from "../context/PacketContext"
import "./HomePage.css"

const HomePage: React.FC = () => {
  const { 
    filteredPackets, 
    clearPackets, 
    isCapturing, 
    startCapture, 
    stopCapture,
    stats,
    searchFilters,
    setSearchFilters,
    loadMorePackets,
    hasMorePackets
  } = usePackets()
  
  const [notification, setNotification] = useState<{message: string; type: 'success' | 'error' | 'info'}|null>(null)
  const history = useHistory()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const contentRef = useRef<HTMLIonContentElement>(null)

  // Show notification function
  const showNotification = useCallback((message: string, type: 'success' | 'error' | 'info' = 'info') => {
    setNotification({ message, type });
    setTimeout(() => {
      setNotification(null);
    }, 4000);
  }, []);

  const handleStartCapture = async () => {
    console.log("HomePage: Start capture button clicked");
    setLoading(true)
    setError(null)
    
    try {
      await startCapture();
      showNotification("Packet capture started successfully", "success");
    } catch (error) {
      console.error("HomePage: Error starting packet capture:", error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      setError("Failed to start packet capture: " + errorMessage);
      showNotification("Failed to start capture", "error");
    } finally {
      setLoading(false)
    }
  }

  const handleStopCapture = async () => {
    console.log("HomePage: Stop capture button clicked");
    setLoading(true)
    try {
      await stopCapture();
      showNotification("Packet capture stopped", "info");
    } catch (error) {
      console.error("HomePage: Error stopping packet capture:", error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      setError("Failed to stop packet capture: " + errorMessage);
      showNotification("Failed to stop capture", "error");
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

  // Handle unified search
  const handleSearchChange = (e: CustomEvent) => {
    const query = e.detail.value || '';
    setSearchFilters({
      ...searchFilters,
      searchQuery: query
    });
  }

  // Handle direction filter
  const handleDirectionChange = (e: CustomEvent) => {
    setSearchFilters({
      ...searchFilters,
      direction: e.detail.value
    });
  }

  // Function to determine row color
  const getRowColor = (protocol: string) => {
    if (protocol === "TCP" || protocol === "HTTP") {
      return "var(--ion-color-success-shade)"
    } else if (protocol === "UDP" || protocol === "DNS") {
      return "var(--ion-color-primary-shade)"
    } else {
      return "var(--ion-color-danger-shade)"
    }
  }

  // Infinite scroll handler
  const handleInfiniteScroll = useCallback((ev: CustomEvent) => {
    console.log("Loading more packets...");
    loadMorePackets();
    setTimeout(() => {
      (ev.target as HTMLIonInfiniteScrollElement).complete();
    }, 500);
  }, [loadMorePackets]);

  return (
    <IonPage className="bg-white dark:bg-gray-900">
      {/* Notification Component */}
      {notification && (
        <div className={`notification-card ${
          notification.type === 'success' ? 'bg-green-500' : 
          notification.type === 'error' ? 'bg-red-500' : 
          'bg-blue-500'
        } text-white p-3 shadow-lg`}>
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

        {/* Stats Dashboard */}
        {stats.totalPackets > 0 && (
          <IonCard className="mb-4 dark:bg-gray-800">
            <IonCardContent>
              <IonGrid>
                <IonRow>
                  <IonCol size="6">
                    <div className="text-center">
                      <h3 className="text-2xl font-bold text-blue-600 dark:text-blue-400">
                        {stats.totalPackets}
                      </h3>
                      <p className="text-sm text-gray-600 dark:text-gray-300">Total Packets</p>
                    </div>
                  </IonCol>
                  <IonCol size="6">
                    <div className="text-center">
                      <h3 className="text-2xl font-bold text-green-600 dark:text-green-400">
                        {(stats.totalBytes / 1024).toFixed(2)} KB
                      </h3>
                      <p className="text-sm text-gray-600 dark:text-gray-300">Total Data</p>
                    </div>
                  </IonCol>
                </IonRow>
                <IonRow className="mt-2">
                  <IonCol size="6">
                    <div className="text-center">
                      <IonBadge color="success">{stats.incomingPackets} ↓</IonBadge>
                      <p className="text-xs text-gray-600 dark:text-gray-300 mt-1">Incoming</p>
                    </div>
                  </IonCol>
                  <IonCol size="6">
                    <div className="text-center">
                      <IonBadge color="warning">{stats.outgoingPackets} ↑</IonBadge>
                      <p className="text-xs text-gray-600 dark:text-gray-300 mt-1">Outgoing</p>
                    </div>
                  </IonCol>
                </IonRow>
              </IonGrid>
            </IonCardContent>
          </IonCard>
        )}
        
        {/* Control Buttons */}
        <IonButton 
          expand="full" 
          onClick={isCapturing ? handleStopCapture : handleStartCapture} 
          disabled={loading} 
          className="mb-3 main-button"
        >
          <span className="font-medium">
            {isCapturing ? "Stop Capture" : "Start Capture"}
          </span>
          {loading && <IonSpinner name="crescent" className="ml-2" />}
        </IonButton>

        <IonButton 
          expand="full" 
          color="secondary" 
          onClick={handleClearPackets} 
          disabled={filteredPackets.length === 0} 
          className="mb-4 clear-button"
        >
          <span className="font-medium">Clear All Packets</span>
        </IonButton>

        {/* Unified Search Bar */}
        <IonSearchbar
          value={searchFilters.searchQuery}
          onIonInput={handleSearchChange}
          placeholder="Search by source, destination, protocol, app, payload..."
          debounce={300}
          className="mb-3"
        />

        {/* Direction Filter */}
        <IonSegment 
          value={searchFilters.direction} 
          onIonChange={handleDirectionChange}
          className="mb-4"
        >
          <IonSegmentButton value="all">
            <IonLabel>All</IonLabel>
          </IonSegmentButton>
          <IonSegmentButton value="incoming">
            <IonLabel>↓ Incoming</IonLabel>
          </IonSegmentButton>
          <IonSegmentButton value="outgoing">
            <IonLabel>↑ Outgoing</IonLabel>
          </IonSegmentButton>
        </IonSegment>

        {/* Packet List with Virtual Scrolling */}
        {filteredPackets.length > 0 ? (
          <>
            <div className="mb-2 text-sm text-gray-600 dark:text-gray-400">
              Showing {filteredPackets.length} packets
            </div>
            <IonList className="packet-list">
              {filteredPackets.map((packet, index) => {
                try {
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
                      style={{ backgroundColor: getRowColor(packet.protocol) }}
                    >
                      <IonLabel>
                        <h2 className="font-bold text-white text-lg mb-1">
                          #{packet.packetNumber}
                          {packet.appName && (
                            <IonBadge color="light" className="ml-2 text-xs">
                              {packet.appName}
                            </IonBadge>
                          )}
                        </h2>
                        <p className="text-white text-sm mb-1">
                          <span className="font-semibold text-gray-100">
                            {packet.direction === 'incoming' ? '↓' : '↑'}
                          </span>
                          {' '}{packet.source} → {packet.destination}
                        </p>
                        <p className="text-white text-sm">
                          <span className="font-semibold text-gray-100">Protocol:</span> {packet.protocol}
                          {' | '}
                          <span className="font-semibold text-gray-100">Size:</span> {packet.size}B
                        </p>
                      </IonLabel>
                    </IonItem>
                  )
                } catch (error) {
                  console.error("Error rendering packet item:", error);
                  return null;
                }
              })}
            </IonList>

            {/* Infinite Scroll */}
            {hasMorePackets && (
              <IonInfiniteScroll
                onIonInfinite={handleInfiniteScroll}
                threshold="100px"
              >
                <IonInfiniteScrollContent
                  loadingText="Loading more packets..."
                  loadingSpinner="bubbles"
                />
              </IonInfiniteScroll>
            )}
          </>
        ) : (
          <div className="empty-state dark:bg-gray-800 p-8 rounded-lg">
            {!isCapturing && (
              <p className="empty-message text-gray-700 dark:text-gray-200 font-medium text-center">
                No packets captured yet. <span className="text-blue-600 dark:text-blue-400 font-bold">Start capturing</span> to see network traffic.
              </p>
            )}
            {isCapturing && (
              <p className="empty-message text-gray-700 dark:text-gray-200 font-medium text-center">
                Capturing Packets... <span className="text-blue-600 dark:text-blue-400 animate-pulse font-bold">Please wait</span>
              </p>
            )}
          </div>
        )}
      </IonContent>
    </IonPage>
  )
}

export default HomePage