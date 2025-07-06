import React, { useEffect, useState } from 'react';
import Header from './components/header';
import Tabs from './components/tabs';
import MonitoringTab from './components/MonitoringTab/MonitoringTab';
import ManualSessionTab from './components/ManualSessionTab/ManualSessionTab';
import './App.css';
import {ServerState, SessionInfo} from "./types/server.ts";

const TABS = [
    { label: 'Monitoring', value: 'monitoring' },
    { label: 'Manual session', value: 'manual' }
];

const initialServerState: ServerState = {
    seleniumStat: {
        total: 0, used: 0, queued: 0, inProgress: 0, sessions: [], queuedRequests: []
    },
    playwrightStat: {
        maxPlaywrightSessionsSize: 0,
        activePlaywrightSessionsSize: 0,
        queuedPlaywrightSessionsSize: 0,
        activePlaywrightSessions: [],
        queuedPlaywrightSessions: []
    }
};

const App: React.FC = () => {
    const [activeTab, setActiveTab] = useState<string>('monitoring');
    const [serverState, setServerState] = useState<ServerState>(initialServerState);
    const [sessions, setSessions] = useState<SessionInfo[]>(serverState.seleniumStat.sessions);

    useEffect(() => {
        const es = new EventSource(
            `${import.meta.env.VITE_SERVER_BASE_URL.replace(/\/$/, '')}/events`
        );
        es.addEventListener('state-update', (event) => {
            try {
                const data = JSON.parse((event as MessageEvent).data);
                setServerState(data);
            } catch (e) {
                // Можно добавить обработку ошибок
            }
        });
        return () => es.close();
    }, []);

    return (
        <div className="App">
            <Header serverState={serverState} />
            <div className="main-layout">
                <div className="tabs-block">
                    <Tabs
                        tabs={TABS}
                        activeTab={activeTab}
                        onTabChange={setActiveTab}
                        align="left"
                        className="vertical"
                    />
                    <div className="tab-content">
                        {activeTab === 'monitoring' && (
                            <MonitoringTab
                                sessions={sessions}
                                setSessions={setSessions}
                            />
                        )}
                        {activeTab === 'manual' && <ManualSessionTab/>}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default App;
