import React, {useEffect, useState} from 'react';
import Header from './components/header';
import Tabs from './components/Tabs';
import MonitoringTab from './components/MonitoringTab/MonitoringTab';
import ManualSessionTab from './components/ManualSessionTab/ManualSessionTab';
import './App.css';
import {ServerState} from "./types/server.ts";

const TABS = [
    { label: 'Monitoring', value: 'monitoring' },
    { label: 'Manual session', value: 'manual' }
];

const App: React.FC = () => {
    const [activeTab, setActiveTab] = useState<string>('monitoring');

    const [serverState, setServerState] = useState<ServerState>({
        total: 0, used: 0, queued: 0, inProgress: 0, sessions: [], queuedRequests: []
    });

    // SSE подписка (примерно как в Header)
    useEffect(() => {
        const es = new EventSource('/events');
        es.addEventListener('state-update', (event) => {
            try {
                const data = JSON.parse((event as MessageEvent).data);
                setServerState(data);
            } catch {}
        });
        return () => es.close();
    }, []);

    return (
        <div className="App">
            <Header />
            <div className="main-layout">
                <div>
                    <Tabs
                        tabs={TABS}
                        activeTab={activeTab}
                        onTabChange={setActiveTab}
                        align="left"
                        className="vertical"
                    />
                    <div className="tab-content">
                        {activeTab === 'monitoring' && (
                            <MonitoringTab sessions={serverState.sessions}/>
                        )}
                        {activeTab === 'manual' && <ManualSessionTab/>}
                    </div>
                </div>

            </div>
        </div>
    );
};

export default App;
