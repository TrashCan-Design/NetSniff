import React, { useState } from 'react';
import {
  IonList,
  IonItem,
  IonLabel,
  IonBadge,
  IonContent,
  IonCard,
  IonCardHeader,
  IonCardSubtitle,
  IonCardContent,
  IonChip,
  IonIcon,
  IonButton,
  IonGrid,
  IonRow,
  IonCol,
  IonSpinner,
  IonAlert,
  IonModal,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonButtons,
  IonBackButton,
  IonText
} from '@ionic/react';
import { arrowUp, arrowDown, time, analytics, close } from 'ionicons/icons';
import { usePackets } from '../context/PacketContext';
import { formatDistanceToNow } from 'date-fns';
import './PacketList.css';

const PacketList: React.FC = () => {
  const { 
    packets, 
    stats, 
    isCapturing, 
    isConnecting,
    hasVpnPermission,
    error,
    requestVpnPermission,
    startCapture, 
    stopCapture, 
    clearPackets 
  } = usePackets();
  const [selectedPacket, setSelectedPacket] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  
  const getSelectedPacketData = () => {
    return packets.find(packet => packet.id === selectedPacket);
  };

  const handleCaptureToggle = async () => {
    try {
      if (isCapturing) {
        await stopCapture();
      } else {
        await startCapture();
      }
    } catch (error) {
      console.error('Failed to toggle capture:', error);
    }
  };

  const handleRequestPermission = async () => {
    try {
      await requestVpnPermission();
    } catch (error) {
      console.error('Failed to request VPN permission:', error);
    }
  };

  const getProtocolColor = (protocol: string) => {
    switch (protocol.toUpperCase()) {
      case 'TCP': return 'primary';
      case 'UDP': return 'secondary';
      case 'ICMP': return 'warning';
      case 'HTTP': return 'success';
      case 'DNS': return 'tertiary';
      default: return 'medium';
    }
  };

  const openPacketDetails = (packetId: string) => {
    setSelectedPacket(packetId);
    setIsModalOpen(true);
  };

  const closePacketDetails = () => {
    setIsModalOpen(false);
    // We can optionally clear selectedPacket when modal is closed
    // or keep it to remember the last selected packet
  };

  return (
    <IonContent className="bg-white dark:bg-gray-900">
      {error && (
        <IonAlert
          isOpen={!!error}
          onDidDismiss={() => clearPackets()}
          header="Error"
          message={error}
          buttons={['OK']}
        />
      )}

      <IonCard className="detail-card dark:bg-gray-800 shadow-lg rounded-xl overflow-hidden mx-2 my-2 w-full">
        <IonCardHeader>
          <IonCardSubtitle className="text-gray-700 dark:text-gray-200 font-medium text-lg">Network Statistics</IonCardSubtitle>
        </IonCardHeader>
        <IonCardContent className="dark:text-white">
          <IonGrid className="w-full p-0">
            <IonRow>
              <IonCol>
                <IonChip className="bg-gradient-to-r from-blue-500 to-blue-600 text-white font-medium">
                  <IonIcon icon={analytics} />
                  <IonLabel>Total: {stats.totalPackets}</IonLabel>
                </IonChip>
              </IonCol>
              <IonCol>
                <IonChip className="bg-gradient-to-r from-green-500 to-green-600 text-white font-medium">
                  <IonIcon icon={arrowDown} />
                  <IonLabel>In: {stats.incomingPackets}</IonLabel>
                </IonChip>
              </IonCol>
              <IonCol>
                <IonChip className="bg-gradient-to-r from-indigo-500 to-purple-600 text-white font-medium">
                  <IonIcon icon={arrowUp} />
                  <IonLabel>Out: {stats.outgoingPackets}</IonLabel>
                </IonChip>
              </IonCol>
            </IonRow>
            <IonRow>
              <IonCol>
                {!hasVpnPermission ? (
                  <IonButton
                    expand="block"
                    onClick={handleRequestPermission}
                    disabled={isConnecting}
                    style={{ minHeight: '48px' }}
                    className="main-button permission-button font-medium"
                  >
                    {isConnecting ? (
                      <>
                        <IonSpinner name="dots" />
                        <span style={{ marginLeft: '8px' }}>Requesting...</span>
                      </>
                    ) : (
                      <span className="font-semibold tracking-wide">Request VPN Permission</span>
                    )}
                  </IonButton>
                ) : (
                  <IonButton
                    expand="block"
                    onClick={handleCaptureToggle}
                    disabled={isConnecting}
                    style={{ minHeight: '48px' }}
                    className={`${isCapturing ? 'stop-button' : 'start-button'} capture-button font-medium`}
                  >
                    {isConnecting ? (
                      <>
                        <IonSpinner name="dots" />
                        <span style={{ marginLeft: '8px' }}>
                          {isCapturing ? 'Stopping...' : 'Starting...'}
                        </span>
                      </>
                    ) : (
                      <span className="font-semibold tracking-wide">
                        {isCapturing ? 'Stop Capture' : 'Start Capture'}
                      </span>
                    )}
                  </IonButton>
                )}
              </IonCol>
              <IonCol>
                <IonButton
                  expand="block"
                  onClick={clearPackets}
                  disabled={isConnecting || isCapturing}
                  style={{ minHeight: '48px' }}
                  className="clear-button font-medium"
                >
                  <span className="font-semibold tracking-wide">Clear</span>
                </IonButton>
              </IonCol>
            </IonRow>
          </IonGrid>
        </IonCardContent>
      </IonCard>

      <IonList className="packet-list bg-white dark:bg-gray-800 rounded-lg shadow-md mx-4 my-4">
        {packets.map((packet) => (
          <IonItem
            key={packet.id}
            button
            onClick={() => openPacketDetails(packet.id)}
            className="packet-item dark:border-gray-700 mb-1.5"
          >
            <IonLabel className="dark:text-white py-2">
              <h2 className="font-bold text-lg mb-2 dark:text-white">
                <IonBadge color={getProtocolColor(packet.protocol)} className="mr-2">
                  {packet.protocol}
                </IonBadge>
                <IonChip 
                  className={`${packet.direction === 'incoming' ? 'bg-gradient-to-r from-green-500 to-green-600' : 'bg-gradient-to-r from-indigo-500 to-purple-600'} text-white font-medium`}
                >
                  <IonIcon icon={packet.direction === 'incoming' ? arrowDown : arrowUp} />
                  <IonLabel>{packet.size} bytes</IonLabel>
                </IonChip>
              </h2>
              <p className="text-gray-700 dark:text-gray-200 mb-1 font-medium">
                {packet.source} → {packet.destination}
              </p>
              <p className="text-gray-600 dark:text-gray-300 text-sm flex items-center">
                <IonIcon icon={time} className="mr-1" />
                {formatDistanceToNow(packet.timestamp, { addSuffix: true })}
              </p>
            </IonLabel>
          </IonItem>
        ))}
      </IonList>

      {/* Packet Detail Modal */}
      <IonModal isOpen={isModalOpen} onDidDismiss={closePacketDetails}>
        {getSelectedPacketData() && (
          <>
            <IonHeader>
              <IonToolbar className="dark:bg-gray-800">
                <IonButtons slot="start">
                  <IonButton onClick={closePacketDetails} className="back-button" fill="clear">
                    <IonIcon icon={close} />
                  </IonButton>
                </IonButtons>
                <IonTitle className="font-medium text-center">Packet Details</IonTitle>
              </IonToolbar>
            </IonHeader>
            <IonContent className="ion-padding bg-white dark:bg-gray-900">
              <IonGrid className="detail-card dark:bg-gray-800 shadow-lg rounded-xl overflow-hidden p-4 mx-2 my-2">
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h2 className="font-bold text-xl mb-2">
                        <IonBadge color={getProtocolColor(getSelectedPacketData()!.protocol)} className="p-2">
                          {getSelectedPacketData()!.protocol}
                        </IonBadge>
                      </h2>
                    </IonText>
                  </IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold">Direction</h3>
                      <p className="text-gray-800 dark:text-white">{getSelectedPacketData()!.direction}</p>
                    </IonText>
                  </IonCol>
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold">Size</h3>
                      <p className="text-gray-800 dark:text-white">{getSelectedPacketData()!.size} bytes</p>
                    </IonText>
                  </IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold">Source</h3>
                      <p className="text-gray-800 dark:text-white">{getSelectedPacketData()!.source}</p>
                    </IonText>
                  </IonCol>
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold">Destination</h3>
                      <p className="text-gray-800 dark:text-white">{getSelectedPacketData()!.destination}</p>
                    </IonText>
                  </IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold">Timestamp</h3>
                      <p className="text-gray-800 dark:text-white">{formatDistanceToNow(getSelectedPacketData()!.timestamp, { addSuffix: true })}</p>
                    </IonText>
                  </IonCol>
                </IonRow>
                <IonRow className="py-2">
                  <IonCol>
                    <IonText className="dark:text-white">
                      <h3 className="text-gray-700 dark:text-gray-200 font-semibold mb-2">Payload</h3>
                    </IonText>
                    <pre className="packet-data bg-gray-100 dark:bg-gray-700 p-4 rounded-lg text-gray-800 dark:text-white overflow-auto font-mono text-sm leading-relaxed mb-2">
                      {getSelectedPacketData()!.payload}
                    </pre>
                  </IonCol>
                </IonRow>
              </IonGrid>
            </IonContent>
          </>
        )}
      </IonModal>
    </IonContent>
  );
};

export default PacketList;
