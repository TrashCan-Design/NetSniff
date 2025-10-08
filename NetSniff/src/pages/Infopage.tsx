import React from 'react';
import {
  IonPage,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonButtons,
  IonBackButton,
  IonCard,
  IonCardHeader,
  IonCardTitle,
  IonCardContent,
  IonBadge,
  IonImg,
  IonText,
  IonList,
  IonItem
} from '@ionic/react';
import './Info.css';

const InfoPage: React.FC = () => {
  return (
    <IonPage className="min-h-screen bg-gradient-to-b from-blue-100 to-gray-100 dark:from-gray-900 dark:to-slate-950">
      <IonHeader>
        <IonToolbar className="bg-gradient-to-r from-blue-600 to-blue-700 text-white shadow-lg dark:from-blue-800 dark:to-blue-900">
          <IonButtons slot="start">
            <IonBackButton defaultHref="/home" />
          </IonButtons>
          <IonTitle className="text-xl font-bold tracking-wide">Info</IonTitle>
        </IonToolbar>
      </IonHeader>
      <IonContent className="p-0 m-0">
        <div className="max-w-4xl mx-auto p-6 animate-fadeIn">
          
          {/* Logo and Version */}
          <IonCard className="mb-6 glass-morphism hover:scale-[1.01] transition-all duration-300">
            <IonImg src="/assets/logo.jpg" alt="NetSniff Logo" className="ion-img-cover" />
            <IonCardContent className="bg-white/90 dark:bg-gray-800/90 dark:text-white">
              <h2 className="pb-2 text-black dark:text-white font-extrabold tracking-tight text-shadow" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '2rem' }}>
                NetSniff - Network Analysis Tool
              </h2>
              <IonBadge color="primary" className="shadow-md bg-gradient-to-r from-indigo-500 to-purple-500 dark:from-indigo-600 dark:to-violet-500 text-white font-medium">
                Version 1.0.0
              </IonBadge>
            </IonCardContent>
          </IonCard>

          {/* VPN Traffic Routing Section */}
          <IonCard className="mb-6 glass-morphism hover:shadow-xl">
            <IonCardHeader>
              <IonCardTitle className="text-blue-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>
                Flow of Data and VPN Traffic Routing
              </IonCardTitle>
            </IonCardHeader>
            <IonCardContent>
              <IonText className="text-gray-700 dark:text-gray-100 mb-4 leading-relaxed">
                When a VPN service is established using VpnService in Android, the system creates a virtual network interface that captures all network traffic originating from the device. This virtual interface ensures that data is routed through the application before reaching the internet.
              </IonText>

              <IonList>
                <IonItem className="flex flex-col items-start p-0 mb-4">
                  <h3 className="text-indigo-700 dark:text-indigo-300 font-bold mb-1">
                    1. Establishing the VPN Tunnel
                  </h3>
                  <IonText className="text-gray-700 dark:text-gray-200 bg-blue-50/50 dark:bg-gray-700/50 rounded-md p-4 shadow-inner w-full">
                    Android's VpnService API creates a virtual TUN interface that captures all network traffic. The system redirects packets through this interface, allowing the NetSniff service to intercept and inspect traffic before forwarding it to its destination.
                  </IonText>
                </IonItem>

                <IonItem className="flex flex-col items-start p-0 mb-4">
                  <h3 className="text-green-700 dark:text-green-300 font-bold mb-1">
                    2. Capturing and Processing Packets
                  </h3>
                  <IonText className="text-gray-700 dark:text-gray-200 bg-green-50/50 dark:bg-gray-700/50 rounded-md p-4 shadow-inner w-full">
                    As network traffic passes through the VPN, packets are captured in real time. The native Android code (written in Java) analyzes each packet and extracts the following data:
                    <IonList className="ml-5 mt-2">
                      <IonItem>• Source & Destination IP Addresses</IonItem>
                      <IonItem>• Packet Length (in bytes)</IonItem>
                      <IonItem>• Network Protocols (TCP, UDP, ICMP, HTTP, DNS)</IonItem>
                      <IonItem>• Direction (incoming/outgoing traffic)</IonItem>
                      <IonItem>• Timestamp (when packet was captured)</IonItem>
                    </IonList>
                  </IonText>
                </IonItem>

                <IonItem className="flex flex-col items-start p-0 mb-4">
                  <h3 className="text-purple-700 dark:text-purple-300 font-bold mb-1">
                    3. Modifying or Forwarding Packets
                  </h3>
                  <IonText className="text-gray-700 dark:text-gray-200 bg-purple-50/50 dark:bg-gray-700/50 rounded-md p-4 shadow-inner w-full">
                    After analyzing the packets, the VPN service:
                    <IonList className="ml-5 mt-2">
                      <IonItem>• Extracts key packet metadata (IP addresses, protocol, size)</IonItem>
                      <IonItem>• Transfers data to the Ionic React app via the Capacitor bridge</IonItem>
                      <IonItem>• Forwards original packets to their destination unmodified</IonItem>
                    </IonList>
                  </IonText>
                </IonItem>
              </IonList>
            </IonCardContent>
          </IonCard>

          {/* VPN Integration Section */}
          <IonCard className="mb-6 glass-morphism hover:shadow-xl">
            <IonCardHeader>
              <IonCardTitle className="text-teal-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>
                VPN Integration in Ionic React App
              </IonCardTitle>
            </IonCardHeader>
            <IonCardContent>
              <IonText className="block bg-gradient-to-r from-blue-50 to-teal-50 dark:from-gray-700 dark:to-slate-700 p-5 rounded-xl shadow-inner border border-blue-100 dark:border-gray-600">
                <h4 className="text-blue-700 dark:text-blue-300 font-semibold mb-2">Frontend (Ionic React UI)</h4>
                <IonList className="ml-5">
                  <IonItem>Displays a real-time dashboard with network traffic visualization.</IonItem>
                  <IonItem>
                    Provides comprehensive packet analytics:
                    <IonList className="ml-5 mt-1">
                      <IonItem>• Protocol distribution statistics.</IonItem>
                      <IonItem>• Incoming/outgoing traffic breakdown.</IonItem>
                    </IonList>
                  </IonItem>
                  <IonItem>Maintains a continuously updated list of captured packets with detailed metadata.</IonItem>
                  <IonItem>Offers start/stop controls for the packet capture process.</IonItem>
                </IonList>

                <h4 className="text-blue-700 dark:text-blue-300 font-semibold mt-4 mb-2">Backend (VpnService in Java/Kotlin)</h4>
                <IonList className="ml-5">
                  <IonItem>Implements Android's VpnService API to create a secure packet interception layer. Processes network packets in real-time using native Java code for optimal performance. Communicates with the frontend through Capacitor's plugin bridge architecture. Maintains memory efficiency by limiting packet storage and using event-based communication.</IonItem>
                </IonList>
              </IonText>
            </IonCardContent>
          </IonCard>

          {/* Deep Packet Inspection & Security */}
          <div className="flex flex-col md:flex-row gap-4 mb-6">
            <IonCard className="flex-1 glass-morphism hover:shadow-xl">
              <IonCardHeader>
                <IonCardTitle className="text-purple-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>
                  Deep Packet Inspection
                </IonCardTitle>
              </IonCardHeader>
              <IonCardContent>
                <IonList className="space-y-3">
                  <IonItem className="bg-purple-50 dark:bg-gray-700 rounded-r-md border-l-4 border-purple-400">
                    <h5 className="text-purple-700 dark:text-purple-300 font-semibold mb-1">Live Traffic Capture</h5>
                    <IonText className="text-gray-700 dark:text-gray-200">Monitors network activity in real time. Works with Ethernet, Wi-Fi, and VPN interfaces.</IonText>
                  </IonItem>
                  <IonItem className="bg-purple-50 dark:bg-gray-700 rounded-r-md border-l-4 border-purple-400">
                    <h5 className="text-purple-700 dark:text-purple-300 font-semibold mb-1">Promiscuous Mode</h5>
                    <IonText className="text-gray-700 dark:text-gray-200">Captures all network traffic on a network segment, beyond the device's own traffic.</IonText>
                  </IonItem>
                  <IonItem className="bg-purple-50 dark:bg-gray-700 rounded-r-md border-l-4 border-purple-400">
                    <h5 className="text-purple-700 dark:text-purple-300 font-semibold mb-1">Monitor Mode (for Wi-Fi)</h5>
                    <IonText className="text-gray-700 dark:text-gray-200">Captures raw wireless frames, including encrypted and control packets.</IonText>
                  </IonItem>
                </IonList>
              </IonCardContent>
            </IonCard>

            <IonCard className="flex-1 glass-morphism hover:shadow-xl">
              <IonCardHeader>
                <IonCardTitle className="text-red-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>
                  Security & Privacy
                </IonCardTitle>
              </IonCardHeader>
              <IonCardContent>
                <IonText className="block bg-red-50 dark:bg-gray-700 p-4 rounded-lg border border-red-100 dark:border-gray-600 shadow-inner">
                  <IonText className="text-gray-700 dark:text-gray-200 mb-4 font-medium block">
                    Handling network traffic requires adherence to security and privacy best practices:
                  </IonText>
                  <ul className="text-gray-700 dark:text-gray-200 ml-5 space-y-3">
                    <li>Captured packet data is stored only temporarily in memory and automatically limited to recent entries.</li>
                    <li>The app includes safety mechanisms to ensure VPN services are properly terminated when the application closes.</li>
                    <li>All traffic inspection occurs locally on the device with no data transmitted to external servers.</li>
                  </ul>
                </IonText>
              </IonCardContent>
            </IonCard>
          </div>

        </div>
      </IonContent>
    </IonPage>
  );
};

export default InfoPage;
