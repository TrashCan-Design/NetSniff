import React from 'react';
import {
  IonPage,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonButtons,
  IonMenuButton,
  IonIcon,
  IonAvatar,
} from '@ionic/react';
import { personCircle } from 'ionicons/icons';
// Tailwind CSS is used instead of the CSS file
// import './About.css';

const AboutUs: React.FC = () => (
  <IonPage id="main-content" className="min-h-screen bg-gradient-to-b from-blue-100 to-gray-100 dark:from-gray-800 dark:to-gray-900">
    <IonHeader>
      <IonToolbar className="bg-gradient-to-r from-blue-600 to-blue-700 text-white shadow-lg">
        <IonButtons slot="start">
          <IonMenuButton />
        </IonButtons>
        <IonTitle className="text-xl font-bold tracking-wide">About Us & Contact</IonTitle>
      </IonToolbar>
    </IonHeader>

    <IonContent className="bg-transparent">
      <div className="flex flex-col items-center p-6 max-w-4xl mx-auto animate-fadeIn">
        <img
          src="/assets/logo.jpg"
          alt="NetSniff Logo"
          className="w-32 h-32 rounded-full mb-6 object-cover border-4 border-blue-500 shadow-lg transform hover:scale-105 transition-transform duration-300"
        />

        <h2 className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-600 to-blue-400 mb-3 tracking-tight">Meet Our Team</h2>
        <p className="text-gray-700 dark:text-gray-300 text-center mb-6 max-w-2xl leading-relaxed">
          We are a passionate group of Computer Science students dedicated to making network analysis simple and efficient.
          This app allows real-time packet sniffing and visualizations on mobile devices.
        </p>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 w-full mb-8 mt-4">
          {[
            {
              id: 1,
              name: "Jay Shah",
              role: "Team Lead",
              skills: [{name: "Networking", color: "blue"}, {name: "Security", color: "green"}, {name: "Mobile Dev", color: "red"}]
            },
            {
              id: 2,
              name: "Krina Rane",
              role: "Frontend Developer",
              skills: [{name: "Frontend", color: "blue"}, {name: "CyberSec", color: "red"}, {name: "App Developer", color: "green"}]
            },
            {
              id: 3,
              name: "Pawan Punjabi",
              role: "Developer",
              skills: [{name: "FrontEnd Developer", color: "green"}]
            }
          ].map((member) => (
            <div key={member.id} className="bg-white/90 dark:bg-gray-800/90 backdrop-blur-sm p-6 rounded-lg shadow-lg flex flex-col items-center transform hover:scale-105 transition-all duration-300 border border-gray-200 dark:border-gray-700">
              <div className="mb-3">
                <IonAvatar className="w-20 h-20 flex items-center justify-center bg-gradient-to-br from-blue-100 to-blue-200 dark:from-blue-800 dark:to-blue-900 shadow-md mb-4">
                  <IonIcon icon={personCircle} className="text-5xl text-blue-600 dark:text-blue-400" />
                </IonAvatar>
              </div>
              <h3 className="text-lg font-semibold text-gray-800 dark:text-white">{member.name}</h3>
              <p className="text-sm text-gray-600 dark:text-gray-400">{member.role}</p>
              <div className="mt-3 flex flex-wrap gap-2 justify-center">
                {member.skills.map((skill, idx) => (
                  <span key={idx} className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-${skill.color}-100 text-${skill.color}-800 dark:bg-${skill.color}-900 dark:text-${skill.color}-200`}>{skill.name}</span>
                ))}
              </div>
            </div>
          ))}
        </div>

        <h2 className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-600 to-blue-400 mb-3 mt-12 tracking-tight">Contact Us</h2>
        <p className="text-gray-700 dark:text-gray-300 text-center mb-6 max-w-2xl leading-relaxed">
          Feel free to reach out to us with any questions or feedback about NetSniff.
        </p>
        
        <div className="w-full max-w-md bg-white/90 dark:bg-gray-800/90 backdrop-blur-sm rounded-lg shadow-lg p-6 border border-gray-200 dark:border-gray-700 mb-8">
          <div className="space-y-4">
            <div className="flex items-center space-x-3 text-gray-800 dark:text-gray-200 hover:text-blue-600 dark:hover:text-blue-400 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span>jay.p.shah@nuv.ac.in</span>
              
            </div>
            <div className="flex items-center space-x-3 text-gray-800 dark:text-gray-200 hover:text-blue-600 dark:hover:text-blue-400 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span>krina.t.rane@nuv.ac.in</span>
              
            </div>

            <div className="flex items-center space-x-3 text-gray-800 dark:text-gray-200 hover:text-blue-600 dark:hover:text-blue-400 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span>pawan.d.punjabi@nuv.ac.in</span>
              
            </div>
            <div className="flex items-center space-x-3 text-gray-800 dark:text-gray-200 hover:text-blue-600 dark:hover:text-blue-400 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" />
              </svg>
              <span>+91 9999999999</span>
            </div>
            <div className="flex items-center space-x-3 text-gray-800 dark:text-gray-200 hover:text-blue-600 dark:hover:text-blue-400 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              <span>Navrachana University</span>
            </div>
          </div>
        </div>
      </div>
    </IonContent>
  </IonPage>
);

export default AboutUs;
