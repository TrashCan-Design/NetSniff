import React, { useState, useEffect } from 'react';
import {
  IonPage, IonHeader, IonToolbar, IonTitle, IonContent,
  IonItem, IonList, IonLabel, IonButton, IonBadge,
  IonButtons, IonMenuButton, IonCard, IonCardHeader,
  IonCardContent, IonCardTitle, IonInput, IonTextarea,
  IonToggle, IonIcon, IonAlert
} from '@ionic/react';
import {
  trashOutline, addCircleOutline, shieldCheckmarkOutline, cloudDownloadOutline
} from 'ionicons/icons';
import { ToyVpn } from '@capacitor/toy-vpn/ToyVpn';

interface BlacklistEntry {
  id: number;
  domain: string;
  added_time: number;
  enabled: boolean;
  description: string;
}

const DbPage: React.FC = () => {
  const [traffic, setTraffic] = useState<any[]>([]);
  const [loadingTraffic, setLoadingTraffic] = useState(false);
  const [blocklist, setBlocklist] = useState<BlacklistEntry[]>([]);
  const [newDomain, setNewDomain] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [loading, setLoading] = useState(false);
  const [alertMessage, setAlertMessage] = useState('');
  const [showAlert, setShowAlert] = useState(false);

  useEffect(() => {
    loadBlocklist();
  }, []);

  const handleLoadOldPackets = async () => {
    setLoadingTraffic(true);
    try {
      const old = await ToyVpn.getSavedTraffic();
      const data = old?.traffic || [];
      setTraffic(data);
    } catch (e) {
      setTraffic([]);
    } finally {
      setLoadingTraffic(false);
    }
  };

  const loadBlocklist = async () => {
    try {
      const result = await ToyVpn.getBlacklist();
      if (result && result.blacklist) {
        setBlocklist(result.blacklist);
      }
    } catch (error) {
      console.error('Failed to load blacklist:', error);
    }
  };

  const handleAddDomain = async () => {
    if (!newDomain.trim()) {
      setAlertMessage('Please enter a domain name');
      setShowAlert(true);
      return;
    }
    setLoading(true);
    try {
      const result = await ToyVpn.addToBlacklist({
        domain: newDomain.trim(),
        description: newDescription.trim()
      });
      if (result.ok) {
        setAlertMessage('Domain added successfully');
        setShowAlert(true);
        setNewDomain('');
        setNewDescription('');
        await loadBlocklist();
      } else {
        setAlertMessage('Failed to add domain');
        setShowAlert(true);
      }
    } catch (error) {
      setAlertMessage('Error adding domain: ' + (error instanceof Error ? error.message : String(error)));
      setShowAlert(true);
    } finally {
      setLoading(false);
    }
  };

  const handleRemoveDomain = async (domain: string) => {
    setLoading(true);
    try {
      const result = await ToyVpn.removeFromBlacklist({ domain });
      if (result.ok) {
        setAlertMessage('Domain removed');
        setShowAlert(true);
        await loadBlocklist();
      } else {
        setAlertMessage('Failed to remove domain');
        setShowAlert(true);
      }
    } catch (error) {
      setAlertMessage('Error removing domain: ' + (error instanceof Error ? error.message : String(error)));
      setShowAlert(true);
    } finally {
      setLoading(false);
    }
  };

  const handleToggleDomain = async (domain: string, enabled: boolean) => {
    try {
      const result = await ToyVpn.setBlacklistEnabled({ domain, enabled });
      if (result.ok) await loadBlocklist();
    } catch (error) {
      setAlertMessage('Error toggling domain');
      setShowAlert(true);
    }
  };

  return (
    <IonPage className="bg-white dark:bg-gray-900">
      <IonHeader>
        <IonToolbar color="primary" className="dark:bg-gray-800">
          <IonButtons slot="start">
            <IonMenuButton />
          </IonButtons>
          <IonTitle className="font-bold">Network Database & Blocklist</IonTitle>
        </IonToolbar>
      </IonHeader>

      <IonContent className="bg-white dark:bg-gray-900">
        <div className="p-4 space-y-4">
          <IonCard className="shadow-md dark:bg-gray-800">
            <IonCardHeader>
              <IonCardTitle className="text-lg dark:text-white flex items-center">
                <IonIcon icon={cloudDownloadOutline} className="mr-2" />
                Saved Traffic Records
              </IonCardTitle>
            </IonCardHeader>
            <IonCardContent>
              <IonButton
                expand="block"
                color="tertiary"
                onClick={handleLoadOldPackets}
                disabled={loadingTraffic}
              >
                Load Saved Packets
              </IonButton>

              <IonList className="mt-3">
                {traffic.length === 0 ? (
                  <IonItem className="dark:bg-gray-700">
                    <IonLabel className="dark:text-white text-center py-2">
                      No saved traffic records found.
                    </IonLabel>
                  </IonItem>
                ) : (
                  traffic.map((r, i) => (
                    <IonItem key={i} className="dark:bg-gray-700 rounded-md mb-2">
                      <IonLabel className="dark:text-white">
                        <h2 className="font-semibold text-base">
                          {r.source_ip}:{r.source_port} → {r.dest_ip}:{r.dest_port}
                        </h2>
                        <p className="text-sm text-gray-400 dark:text-gray-300">
                          {new Date(r.timestamp).toLocaleString()} • {r.protocol} • {r.direction} • {r.size} B
                        </p>
                        {r.domain && (
                          <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">Domain: {r.domain}</p>
                        )}
                        {r.app_name && (
                          <p className="text-xs text-gray-400 dark:text-gray-300">App: {r.app_name}</p>
                        )}
                      </IonLabel>
                    </IonItem>
                  ))
                )}
              </IonList>
            </IonCardContent>
          </IonCard>

          <IonCard className="shadow-md dark:bg-gray-800">
            <IonCardHeader>
              <IonCardTitle className="text-lg dark:text-white flex items-center">
                <IonIcon icon={shieldCheckmarkOutline} className="mr-2" />
                Domain Blocklist ({blocklist.length})
              </IonCardTitle>
            </IonCardHeader>
            <IonCardContent>
              <IonItem className="dark:bg-gray-700 rounded-md mb-2">
                <IonLabel position="stacked" className="dark:text-white">Domain</IonLabel>
                <IonInput
                  value={newDomain}
                  placeholder="example.com or *.ads.com"
                  onIonChange={e => setNewDomain(e.detail.value!)}
                  className="dark:text-white"
                />
              </IonItem>

              <IonItem className="dark:bg-gray-700 rounded-md mb-3">
                <IonLabel position="stacked" className="dark:text-white">Description (Optional)</IonLabel>
                <IonTextarea
                  value={newDescription}
                  placeholder="Reason for blocking"
                  onIonChange={e => setNewDescription(e.detail.value!)}
                  rows={2}
                  className="dark:text-white"
                />
              </IonItem>

              <IonButton
                expand="block"
                color="success"
                onClick={handleAddDomain}
                disabled={loading || !newDomain.trim()}
              >
                <IonIcon icon={addCircleOutline} slot="start" />
                Add Domain
              </IonButton>

              <IonList className="mt-3">
                {blocklist.length === 0 ? (
                  <IonItem className="dark:bg-gray-700">
                    <IonLabel className="text-center py-4 dark:text-white">
                      No domains in blocklist
                    </IonLabel>
                  </IonItem>
                ) : (
                  blocklist.map((entry) => (
                    <IonItem key={entry.id} className="dark:bg-gray-700 rounded-md mb-2">
                      <IonLabel className="dark:text-white">
                        <h2 className="font-semibold">{entry.domain}</h2>
                        {entry.description && (
                          <p className="text-sm text-gray-400 dark:text-gray-300">{entry.description}</p>
                        )}
                        <p className="text-xs text-gray-500 dark:text-gray-400">
                          Added: {new Date(entry.added_time).toLocaleString()}
                        </p>
                      </IonLabel>
                      <IonToggle
                        checked={entry.enabled}
                        onIonChange={e => handleToggleDomain(entry.domain, e.detail.checked)}
                        slot="end"
                        className="mr-2"
                      />
                      <IonButton
                        fill="clear"
                        color="danger"
                        onClick={() => handleRemoveDomain(entry.domain)}
                        slot="end"
                      >
                        <IonIcon icon={trashOutline} />
                      </IonButton>
                    </IonItem>
                  ))
                )}
              </IonList>
            </IonCardContent>
          </IonCard>
        </div>
      </IonContent>

      <IonAlert
        isOpen={showAlert}
        onDidDismiss={() => setShowAlert(false)}
        header="Info"
        message={alertMessage}
        buttons={['OK']}
      />
    </IonPage>
  );
};

export default DbPage;
