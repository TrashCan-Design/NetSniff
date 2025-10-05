import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './tailwind.css';

// Note: Plugin registration is now handled in plugins/ToyVpn.ts

const container = document.getElementById('root');
const root = createRoot(container!);
root.render(
  <App />
);