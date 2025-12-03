

## Project Description

NetSniff – Network Analysis Tool Overview

NetSniff is a powerful network analysis tool designed to monitor, inspect, and analyze real-time network traffic directly on an Android device. Leveraging Android's native VpnService API, the application intercepts all outbound and inbound traffic through a virtual interface, allowing deep inspection of packet-level data. This ensures that every packet transmitted or received is first routed through the app, enabling comprehensive traffic analysis.

How VPN-Based Traffic Routing Works

The core of NetSniff's functionality lies in its use of a virtual TUN interface provided by Android's VpnService. Once the VPN is established, all network packets from the device are funneled through this virtual interface. This allows NetSniff to intercept the packets before they reach their intended destination on the internet. The app uses this mechanism to monitor the flow of data and reroute traffic through its internal processing engine.

Packet Capture and Analysis Process

As traffic is routed through the virtual VPN interface, the application captures packets in real time. These packets are then analyzed using native Android code written in Java for optimal performance. During this process, NetSniff extracts essential metadata such as source and destination IP addresses, packet size, protocol type (e.g., TCP, UDP, ICMP, HTTP, DNS), traffic direction, and timestamps. The captured data is then transferred to the Ionic React frontend via a Capacitor plugin bridge, allowing real-time display and interaction within the app interface.

## 🛠️ Installation & Run Instructions
📦 Install Dependencies

    npm install

This installs all required Node.js packages.

⚠️ Fix Android Import Errors

If you face import errors for Android, follow these steps:

Open the Android project:

    ionic cap open android

In Android Studio, go to the File menu and click:

<b>File → Sync Project with Gradle Files</b>

🔁 Sync & Build the App

    ionic cap sync android

    ionic build

📱 Run the App on a Device

To run the app:

    ionic cap run android --external

🔹 Omit --external if you're not using an external physical device.




## Key Features

📡 VPN-based Traffic Capture Uses Android's VpnService to route all traffic through a virtual interface for inspection.

🔍 Real-Time Packet Analysis Captures and processes each packet to extract IPs, protocols, size, direction, and timestamp.

📊 Live Dashboard Visualization Displays packet metadata, protocol stats, and traffic trends in the Ionic UI.

🔁 Bidirectional Communication Native Java code sends analysis results to the React app via Capacitor.

🔐 Security & Privacy Focused Keeps all analysis local, limits in-memory data, and terminates VPN safely.

🧠 Deep Packet Inspection Support Offers advanced features like Promiscuous Mode and Monitor Mode (for Wi-Fi) to capture raw frames.

🖥️ Frontend Controls Provides buttons to start/stop packet capture, and filters for detailed inspection.

## Tech Stack

🖥️ Frontend (UI Layer)

⚛️ Ionic React Cross-platform mobile UI framework built on React.

🧩 React + TypeScript Component-based architecture with type-safe logic for better scalability and maintainability.

🔌 Capacitor Acts as a bridge between the React frontend and native Android functionalities.

🔧 Backend (Native Android Layer)

☕ Java Used for implementing the VPN logic and packet capture service.

🛡️ Android VpnService API Enables creation of a virtual VPN interface to intercept and inspect all network traffic on the device.
