import React, { useState } from 'react';
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
  IonSegment,
  IonSegmentButton,
  IonLabel,
  IonCard,
  IonCardContent,
  IonIcon
} from '@ionic/react';
import { useParams, useHistory } from 'react-router-dom';
import { usePackets } from '../context/PacketContext';
import './PacketDetailPage.css';

const PacketDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { packets } = usePackets();
  const history = useHistory();
  const [selectedSegment, setSelectedSegment] = useState('info');

  const packet = packets.find(p => p.id === id);

  if (!packet) {
    return (
      <IonPage className="bg-white dark:bg-gray-900">
        <IonHeader>
          <IonToolbar className="dark:bg-gray-800">
            <IonTitle className="font-medium text-center">Packet Not Found</IonTitle>
          </IonToolbar>
        </IonHeader>
        <IonContent className="ion-padding bg-white dark:bg-gray-900">
          <div className="flex flex-col items-center justify-center h-full p-8">
            <p className="text-gray-800 dark:text-white text-lg mb-6 text-center">Packet with ID {id} not found.</p>
            <IonButton onClick={() => history.goBack()} className="main-button w-full max-w-xs">Back to Home</IonButton>
          </div>
        </IonContent>
      </IonPage>
    );
  }

  const handleSegmentChange = (event: any) => {
    setSelectedSegment(event.detail.value);
  };

  return (
    <IonPage className="bg-white dark:bg-gray-900">
      <IonHeader>
        <IonToolbar className="dark:bg-gray-800">
          <IonButton slot="start" onClick={() => history.goBack()} fill="clear" className="back-button">
            <IonIcon icon="arrow-back" />
          </IonButton>
          <IonTitle className="font-medium text-center">Packet Details</IonTitle>
        </IonToolbar>
      </IonHeader>
      <IonContent className="ion-padding bg-white dark:bg-gray-900">
        <IonSegment value={selectedSegment} onIonChange={handleSegmentChange} className="dark:bg-gray-800 dark:text-white segment-container">
          <IonSegmentButton value="info" className="segment-button">
            <IonLabel className="font-medium">Packet Info</IonLabel>
          </IonSegmentButton>
          <IonSegmentButton value="data" className="segment-button">
            <IonLabel className="font-medium">Data</IonLabel>
          </IonSegmentButton>
        </IonSegment>

        {selectedSegment === 'info' && (
          <IonCard className="detail-card dark:bg-gray-800 dark:text-white shadow-lg rounded-xl">
            <IonCardContent>
              <IonGrid className="p-2">
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Timestamp:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{new Date(packet.timestamp).toLocaleString()}</IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Source:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{packet.source}</IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Destination:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{packet.destination}</IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Protocol:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{packet.protocol}</IonCol>
                </IonRow>
                <IonRow className="py-2 border-b border-gray-200 dark:border-gray-700">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Direction:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{packet.direction}</IonCol>
                </IonRow>
                <IonRow className="py-2">
                  <IonCol size="4" className="text-gray-700 dark:text-gray-200 font-semibold">Size:</IonCol>
                  <IonCol size="8" className="text-gray-800 dark:text-white">{packet.size} bytes</IonCol>
                </IonRow>
              </IonGrid>
            </IonCardContent>
          </IonCard>
        )}

        {selectedSegment === 'data' && (
          <IonCard className="detail-card dark:bg-gray-800 dark:text-white shadow-lg rounded-xl">
            <IonCardContent>
              <pre className="packet-data bg-gray-100 dark:bg-gray-700 p-5 rounded-lg text-gray-800 dark:text-white overflow-auto font-mono text-sm leading-relaxed">{packet.payload}</pre>
            </IonCardContent>
          </IonCard>
        )}
      </IonContent>
    </IonPage>
  );
};

export default PacketDetailPage;