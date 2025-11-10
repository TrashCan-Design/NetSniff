// CHANGES MADE BY KRINA: ADDDED DbPAGE ROUTING AND TAB
import { IonApp, 
  IonRouterOutlet, 
  IonHeader, 
  IonToolbar, 
  IonTitle, 
  IonContent, 
  IonPage, 
  setupIonicReact, 
  IonTabs,
  IonTabBar,
  IonTabButton,
  IonIcon,
  IonLabel,
  IonButtons,
  IonMenuButton,
} from '@ionic/react';
import { IonReactRouter } from '@ionic/react-router';
import { Route, Redirect } from 'react-router-dom';
import { home, moon, informationCircle, list, menu, mailOpen } from 'ionicons/icons';
import { useState } from 'react';
import { ChakraProvider } from '@chakra-ui/react';

/* Core CSS required for Ionic components to work properly */
import '@ionic/react/css/core.css';

/* Basic CSS for apps built with Ionic */
import '@ionic/react/css/normalize.css';
import '@ionic/react/css/structure.css';
import '@ionic/react/css/typography.css';

/* Optional CSS utils that can be commented out */
import '@ionic/react/css/padding.css';
import '@ionic/react/css/float-elements.css';
import '@ionic/react/css/text-alignment.css';
import '@ionic/react/css/text-transformation.css';
import '@ionic/react/css/flex-utils.css';
import '@ionic/react/css/display.css';

/* Theme variables */
import './theme/variables.css';

import { PacketProvider } from './context/PacketContext';
import PacketList from './components/PacketList';
import PacketDetailPage from './pages/PacketDetailPage';
import AboutPage from './pages/About';
import Infopage from './pages/Infopage';
import DbPage from './pages/DbPage';


setupIonicReact();

const App: React.FC = () => {
  const [isDarkMode, setIsDarkMode] = useState(false);

  const toggleDarkMode = () => {
    const newDarkMode = !isDarkMode;
    setIsDarkMode(newDarkMode);
    // Apply dark class to both body and root element (document.documentElement)
    document.body.classList.toggle("dark", newDarkMode);
    document.documentElement.classList.toggle("dark", newDarkMode);
    
    // Also set the class on root for our :root.dark selectors
    if (newDarkMode) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
  };

  return (
    <IonApp>
      <IonReactRouter>
        <PacketProvider>
          <IonTabs>
            <IonRouterOutlet>
              <Route exact path="/home">
                <IonPage>
                  <IonHeader>
                    <IonToolbar>
                      <IonButtons slot="start">
                        <IonMenuButton>
                          <IonIcon icon={menu} />
                        </IonMenuButton>
                      </IonButtons>
                      <IonTitle></IonTitle>
                    </IonToolbar>
                  </IonHeader>
                  <IonContent className="ion-padding">
                    <PacketList />
                  </IonContent>
                </IonPage>
              </Route>
              <Route exact path="/about" component={AboutPage} />
              <Route exact path="/info" component={Infopage} />
              <Route exact path="/packet/:id" component={PacketDetailPage} />
              <Route exact path="/db" component={DbPage} />
              <Route exact path="/">
                <Redirect to="/home" />
              </Route>
            </IonRouterOutlet>

            <IonTabBar slot="bottom">
              <IonTabButton tab="info" href="/info">
                <IonIcon icon={home} />
                <IonLabel>Home</IonLabel>
              </IonTabButton>

              <IonTabButton tab="packets" href="/home">
                <IonIcon icon={list} />
                <IonLabel>Packets</IonLabel>
              </IonTabButton>
              
              <IonTabButton tab="db" href="/db">
                <IonIcon icon={informationCircle} />
                <IonLabel>DB</IonLabel>
              </IonTabButton>


              <IonTabButton tab="darkmode" onClick={toggleDarkMode}>
                <IonIcon icon={moon} />
                <IonLabel>Dark Mode</IonLabel>
              </IonTabButton>

              <IonTabButton tab="about" href="/about">
                <IonIcon icon={mailOpen} />
                <IonLabel>Contactus</IonLabel>
              </IonTabButton>
            </IonTabBar>
          </IonTabs>
        </PacketProvider>
      </IonReactRouter>
    </IonApp>
  );
};

export default App;
