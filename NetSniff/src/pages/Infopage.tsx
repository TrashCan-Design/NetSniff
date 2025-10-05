import React from 'react';
import {
  ChakraProvider,
  Box,
  Heading,
  Text,
  Stack,
  Button,
  Image,
  Badge,
  createSystem,
  List,
  defaultSystem
} from "@chakra-ui/react";
import './Info.css';

import {
  IonPage,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonButtons,
  IonBackButton
} from "@ionic/react";

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
        <ChakraProvider value={defaultSystem}>
          <Box className="max-w-4xl mx-auto p-6 animate-fadeIn">
            <Box borderWidth="1px" borderRadius="lg" overflow="hidden" mb={6} boxShadow="lg" className="backdrop-blur-sm bg-white/90 dark:bg-gray-800/90 dark:border-gray-700 transform hover:scale-[1.01] transition-all duration-300 dark:shadow-blue-900/20">
              <Image
                src="/assets/logo.jpg"
                alt="NetSniff Logo"
                objectFit="cover"
                height="200px"
                width="100%"
              />
              <Box p={6} className="bg-white/90 dark:bg-gray-800/90 dark:text-white">
                <div className="divide-y divide-gray-200 dark:divide-gray-700 space-y-4 flex flex-col items-start">
                  <Heading size="lg" className="pb-2 text-black dark:text-white font-extrabold tracking-tight text-shadow" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '2rem' }}>NetSniff - Network Analysis Tool</Heading>
                  <Badge colorScheme="blue" fontSize="0.8em" px={2} py={1} className="rounded-full shadow-md bg-gradient-to-r from-indigo-500 to-purple-500 dark:from-indigo-600 dark:to-violet-500 text-white font-medium">Version 1.0.0</Badge>
                  
                </div>
              </Box>
            </Box>
          
            
            {/* VPN Traffic Routing Section */}
            <Box borderWidth="1px" borderRadius="lg" p={6} mb={6} className="glass-morphism backdrop-blur-sm bg-white/90 dark:bg-gray-800/90 dark:border-gray-700 shadow-lg transition-all duration-300 ease-in-out dark:shadow-blue-900/20 hover:shadow-xl">
              <Heading size="md" mb={4} className="relative inline-block after:content-[''] after:absolute after:w-full after:h-1 after:bg-blue-400 dark:after:bg-blue-600 after:left-0 after:bottom-0 after:rounded-full pb-2 text-blue-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>Flow of Data and VPN Traffic Routing</Heading>
              
              <Text className="text-gray-700 dark:text-gray-100 mb-4 leading-relaxed">
                When a VPN service is established using VpnService in Android, the system creates a virtual network interface that captures all network traffic originating from the device. This virtual interface ensures that data is routed through the application before reaching the internet.
              </Text>
              
              <div className="mt-6 space-y-4">
                <div className="border-b border-gray-200 dark:border-gray-700">
                  <div className="py-4 dark:text-white hover:bg-blue-50 dark:hover:bg-gray-700 rounded-md w-full text-left font-bold cursor-pointer">
                    <span className="text-indigo-700 dark:text-indigo-300">
                      <span className="mr-2 text-xl">1.</span> Establishing the VPN Tunnel
                    </span>
                  </div>
                  <div className="text-gray-700 dark:text-gray-200 bg-blue-50/50 dark:bg-gray-700/50 rounded-md mt-1 mb-2 p-4 shadow-inner">
                    <ul className="ml-5 space-y-2">
                      <li>Android's VpnService API creates a virtual TUN interface that captures all network traffic. The system redirects packets through this interface, allowing the NetSniff service to intercept and inspect traffic before forwarding it to its destination.</li>
                    </ul>
                  </div>
                </div>

                <div className="border-b border-gray-200 dark:border-gray-700">
                  <div className="py-4 dark:text-white hover:bg-green-50 dark:hover:bg-gray-700 rounded-md w-full text-left font-bold cursor-pointer">
                    <span className="text-green-700 dark:text-green-300">
                      <span className="mr-2 text-xl">2.</span> Capturing and Processing Packets
                    </span>
                  </div>
                  <div className="text-gray-700 dark:text-gray-200 bg-green-50/50 dark:bg-gray-700/50 rounded-md mt-1 mb-2 p-4 shadow-inner">
                    <Text mb={3}>As network traffic passes through the VPN, packets are captured in real time. The native Android code (written in Java) analyzes each packet and extracts the following data:</Text>
                    <List.Root className="ml-5 space-y-2">
                      <List.Item>• Source & Destination IP Addresses</List.Item>
                      <List.Item>• Packet Length (in bytes)</List.Item>
                      <List.Item>• Network Protocols (TCP, UDP, ICMP, HTTP, DNS)</List.Item>
                      <List.Item>• Direction (incoming/outgoing traffic)</List.Item>
                      <List.Item>• Timestamp (when packet was captured)</List.Item>
                    </List.Root>
                  </div>
                </div>

                <div className="border-b border-gray-200 dark:border-gray-700">
                  <div className="py-4 dark:text-white hover:bg-purple-50 dark:hover:bg-gray-700 rounded-md w-full text-left font-bold cursor-pointer">
                    <span className="text-purple-700 dark:text-purple-300">
                      <span className="mr-2 text-xl">3.</span> Modifying or Forwarding Packets
                    </span>
                  </div>
                  <div className="text-gray-700 dark:text-gray-200 bg-purple-50/50 dark:bg-gray-700/50 rounded-md mt-1 mb-2 p-4 shadow-inner">
                    <Text mb={3}>After analyzing the packets, the VPN service :</Text>
                    <List.Root className="ml-5 space-y-2">
                      <List.Item>• Extracts key packet metadata (IP addresses, protocol, size)</List.Item>
                      <List.Item>• Transfers data to the Ionic React app via the Capacitor bridge</List.Item>
                      <List.Item>• Forwards original packets to their destination unmodified</List.Item>
                    </List.Root>
                  </div>
                </div>
              </div>
            </Box>
            
            {/* VPN Integration Section */}
            <Box borderWidth="1px" borderRadius="lg" p={6} mb={6} className="glass-morphism backdrop-blur-sm bg-white/90 dark:bg-gray-800/90 dark:border-gray-700 shadow-lg transition-all duration-300 ease-in-out dark:shadow-blue-900/20 hover:shadow-xl">
              <Heading size="md" mb={4} className="relative inline-block after:content-[''] after:absolute after:w-full after:h-1 after:bg-teal-400 dark:after:bg-teal-600 after:left-0 after:bottom-0 after:rounded-full pb-2 text-teal-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>VPN Integration in Ionic React App</Heading>
              
              <Box className="bg-gradient-to-r from-blue-50 to-teal-50 dark:from-gray-700 dark:to-slate-700 p-5 rounded-xl shadow-inner my-4 border border-blue-100 dark:border-gray-600">
                <div className="space-y-4">
                  <Box>
                    <Heading size="sm" mb={2} className="text-blue-700 dark:text-blue-300">Frontend (Ionic React UI)</Heading>
                    <List.Root className="ml-5 space-y-2 text-gray-700 dark:text-gray-200">
                      <List.Item>Displays a real-time dashboard with network traffic visualization.</List.Item>
                      <List.Item>
                        Provides comprehensive packet analytics:
                        <List.Root className="ml-5 mt-1 space-y-1">
                          <List.Item>• Protocol distribution statistics.</List.Item>
                          <List.Item>• Incoming/outgoing traffic breakdown.</List.Item>
                        </List.Root>
                      </List.Item>
                      <List.Item>Maintains a continuously updated list of captured packets with detailed metadata.</List.Item>
                      <List.Item>Offers start/stop controls for the packet capture process.</List.Item>
                    </List.Root>
                  </Box>
                  
                  <div className="h-px bg-blue-200 dark:bg-gray-600 my-3"></div>
                  
                  <Box>
                    <Heading size="sm" mb={2} className="text-blue-700 dark:text-blue-300">Backend (VpnService in Java/Kotlin)</Heading>
                    <List.Root className="ml-5 space-y-2 text-gray-700 dark:text-gray-200">
                      <List.Item>Implements Android's VpnService API to create a secure packet interception layer. Processes network packets in real-time using native Java code for optimal performance. Communicates with the frontend through Capacitor's plugin bridge architecture. Maintains memory efficiency by limiting packet storage and using event-based communication.</List.Item>
                    </List.Root>
                  </Box>
                </div>
              </Box>
            </Box>
            
            {/* Features for Packet Inspection and Security Considerations */}
            <div className="flex flex-col md:flex-row gap-4 mb-6">
              <Box flex={1} borderWidth="1px" borderRadius="lg" p={6} className="glass-morphism backdrop-blur-sm bg-white/90 dark:bg-gray-800/90 dark:border-gray-700 shadow-lg transition-all duration-300 ease-in-out dark:shadow-blue-900/20 hover:shadow-xl">
                <Heading size="md" mb={4} className="relative inline-block after:content-[''] after:absolute after:w-full after:h-1 after:bg-purple-400 dark:after:bg-purple-600 after:left-0 after:bottom-0 after:rounded-full pb-2 text-purple-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>Deep Packet Inspection</Heading>
                
                <div className="space-y-3 mt-4">
                  <Box p={3} borderLeft="4px" borderColor="purple.400" className="bg-purple-50 dark:bg-gray-700 rounded-r-md">
                    <Heading size="sm" className="text-purple-700 dark:text-purple-300 mb-2">Live Traffic Capture</Heading>
                    <Text className="text-gray-700 dark:text-gray-200">Monitors network activity in real time. Works with Ethernet, Wi-Fi, and VPN interfaces.</Text>
                  </Box>
                  
                  <Box p={3} borderLeft="4px" borderColor="purple.400" className="bg-purple-50 dark:bg-gray-700 rounded-r-md">
                    <Heading size="sm" className="text-purple-700 dark:text-purple-300 mb-2">Promiscuous Mode</Heading>
                    <Text className="text-gray-700 dark:text-gray-200">Captures all network traffic on a network segment, beyond the device's own traffic.</Text>
                  </Box>
                  
                  <Box p={3} borderLeft="4px" borderColor="purple.400" className="bg-purple-50 dark:bg-gray-700 rounded-r-md">
                    <Heading size="sm" className="text-purple-700 dark:text-purple-300 mb-2">Monitor Mode (for Wi-Fi)</Heading>
                    <Text className="text-gray-700 dark:text-gray-200">Captures raw wireless frames, including encrypted and control packets.</Text>
                  </Box>
                </div>
              </Box>
              
              <Box flex={1} borderWidth="1px" borderRadius="lg" p={6} className="glass-morphism backdrop-blur-sm bg-white/90 dark:bg-gray-800/90 dark:border-gray-700 shadow-lg transition-all duration-300 ease-in-out dark:shadow-blue-900/20 hover:shadow-xl">
                <Heading size="md" mb={4} className="relative inline-block after:content-[''] after:absolute after:w-full after:h-1 after:bg-red-400 dark:after:bg-red-600 after:left-0 after:bottom-0 after:rounded-full pb-2 text-red-600 dark:text-white" style={{ fontFamily: '"Times New Roman", Times, serif', fontSize: '1.5rem' }}>Security & Privacy</Heading>
                
                <Box className="bg-red-50 dark:bg-gray-700 p-4 rounded-lg border border-red-100 dark:border-gray-600 shadow-inner">
                  <Text className="text-gray-700 dark:text-gray-200 mb-4 font-medium">Handling network traffic requires adherence to security and privacy best practices:</Text>
                  
                  <ul className="text-gray-700 dark:text-gray-200 ml-5 space-y-3">
                    <li>Captured packet data is stored only temporarily in memory and automatically limited to recent entries.</li>
                    <li>The app includes safety mechanisms to ensure VPN services are properly terminated when the application closes.</li>
                    <li>All traffic inspection occurs locally on the device with no data transmitted to external servers.</li>
                  </ul>

                </Box>
              </Box>
            </div>
          </Box>
        </ChakraProvider>
      </IonContent>
    </IonPage>
  );
};

export default InfoPage;
