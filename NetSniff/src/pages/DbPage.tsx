import React, { useState } from 'react';
import {
  IonPage, IonHeader, IonToolbar, IonTitle, IonContent,
  IonItem, IonList, IonLabel, IonButton, IonBadge
} from '@ionic/react';
import { ToyVpn } from '@capacitor/toy-vpn/ToyVpn';

const DbPage: React.FC = () => {
  const [traffic, setTraffic] = useState<any[]>([]);

  // ✅ Load only saved (old) traffic from SQLite
  const handleLoadOldPackets = async () => {
    try {
      const old = await ToyVpn.getSavedTraffic();
      const data = old?.traffic || [];
      setTraffic(data);
      console.log(`✅ Loaded ${data.length} saved packets.`);
    } catch (e) {
      console.error('❌ Error loading saved packets:', e);
      setTraffic([]);
    }
  };

  return (
    <IonPage className="bg-white dark:bg-gray-900">
      <IonHeader>
        <IonToolbar className="dark:bg-gray-800">
          <IonTitle>Database Viewer</IonTitle>
        </IonToolbar>
      </IonHeader>

      <IonContent className="ion-padding">
        {/* ===== BUTTON TO LOAD OLD PACKETS ===== */}
        <div className="flex justify-center mb-4">
          <IonButton expand="block" color="primary" onClick={handleLoadOldPackets}>
            Load Saved Packets
          </IonButton>
        </div>

        {/* ===== TRAFFIC SECTION ===== */}
        <h3 className="text-lg font-semibold mb-2">Saved Traffic</h3>
        <IonList>
          {traffic.map((r) => (
            <IonItem key={r.id} className="dark:bg-gray-800 rounded-md my-1">
              <IonLabel>
                <h2 className="font-semibold">
                  {r.source_ip}:{r.source_port} → {r.dest_ip}:{r.dest_port}
                </h2>
                <p>
                  {new Date(r.timestamp).toLocaleString()} • {r.protocol} • {r.direction} • {r.size} B
                </p>
                {r.domain && <p>Domain: {r.domain}</p>}
                {r.app_name && <IonBadge color="tertiary">{r.app_name}</IonBadge>}
              </IonLabel>
            </IonItem>
          ))}
          {traffic.length === 0 && (
            <IonItem>
              <IonLabel>No saved traffic records found.</IonLabel>
            </IonItem>
          )}
        </IonList>
      </IonContent>
    </IonPage>
  );
};

export default DbPage;
