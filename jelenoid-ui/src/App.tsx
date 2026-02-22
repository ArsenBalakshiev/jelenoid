import React, { useEffect, useState } from 'react';
import Header from './components/header';
import Tabs from './components/tabs';
import MonitoringTab from './components/MonitoringTab/MonitoringTab';
import ManualSessionTab from './components/ManualSessionTab/ManualSessionTab';
import './App.css';
import { ServerState, MonitoringSession } from "./types/server";

const TABS = [
    { label: 'Monitoring', value: 'monitoring' },
    { label: 'Manual session', value: 'manual' }
];

const initialServerState: ServerState = {
    seleniumStat: {
        total: 0, used: 0, queued: 0, inProgress: 0,
        activeSeleniumSessions: [],
        queuedSeleniumSession: []
    },
    playwrightStat: {
        maxPlaywrightSessionsSize: 0,
        activePlaywrightSessionsSize: 0,
        queuedPlaywrightSessionsSize: 0,
        activePlaywrightSessions: [],
        queuedPlaywrightSessions: []
    }
};

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected';

const App: React.FC = () => {
    const [activeTab, setActiveTab] = useState<string>('monitoring');
    const [serverState, setServerState] = useState<ServerState>(initialServerState);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('connecting');

    useEffect(() => {
        setConnectionStatus('connecting');
        const es = new EventSource(
            `${import.meta.env.VITE_SERVER_BASE_URL.replace(/\/$/, '')}/events`
        );

        es.onopen = () => setConnectionStatus('connected');
        es.onerror = () => {
            if (es.readyState === EventSource.CLOSED) setConnectionStatus('disconnected');
        };

        es.addEventListener('state-update', (event) => {
            try {
                const data = JSON.parse((event as MessageEvent).data);

                setServerState(prevState => ({
                    seleniumStat: {
                        ...prevState.seleniumStat,
                        ...data?.seleniumStat,
                        activeSeleniumSessions: Array.isArray(data?.seleniumStat?.activeSeleniumSessions)
                            ? data.seleniumStat.activeSeleniumSessions : [],
                        queuedSeleniumSession: Array.isArray(data?.seleniumStat?.queuedSeleniumSession)
                            ? data.seleniumStat.queuedSeleniumSession : []
                    },
                    playwrightStat: {
                        ...prevState.playwrightStat,
                        ...data?.playwrightStat,
                        activePlaywrightSessions: Array.isArray(data?.playwrightStat?.activePlaywrightSessions)
                            ? data.playwrightStat.activePlaywrightSessions : [],
                        queuedPlaywrightSessions: Array.isArray(data?.playwrightStat?.queuedPlaywrightSessions)
                            ? data.playwrightStat.queuedPlaywrightSessions : []
                    }
                }));
            } catch (e) {
                console.error("Failed to parse SSE message", e);
            }
        });
        return () => es.close();
    }, []);

    const monitoringSessions: MonitoringSession[] = [
        ...serverState.seleniumStat.activeSeleniumSessions.map(s => ({ ...s, kind: 'selenium' as const })),
        ...serverState.playwrightStat.activePlaywrightSessions.map(s => ({ ...s, kind: 'playwright' as const }))
    ];

    return (
        <div className="App">
            <Header serverState={serverState} connectionStatus={connectionStatus} />
            <div className="main-layout">
                <div className="tabs-block">
                    <Tabs
                        tabs={TABS}
                        activeTab={activeTab}
                        onTabChange={setActiveTab}
                        align="left"
                    />
                    <div className="tab-content">
                        {activeTab === 'monitoring' && (
                            <MonitoringTab sessions={monitoringSessions} />
                        )}
                        {activeTab === 'manual' && <ManualSessionTab/>}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default App;
