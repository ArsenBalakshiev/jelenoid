import React, { useState, useEffect } from 'react';
import { ServerState } from '../../types/server';
import './Header.css';

interface HeaderProps {
    serverState: ServerState;
}

const Header: React.FC<HeaderProps> = ({ serverState }) => {
    const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');

    useEffect(() => {
        setConnectionStatus('connecting');
        const eventSource = new EventSource(
            `${import.meta.env.VITE_SERVER_BASE_URL.replace(/\/$/, '')}/events`
        );

        eventSource.onopen = () => {
            setConnectionStatus('connected');
        };

        eventSource.onerror = () => {
            if (eventSource.readyState === EventSource.CLOSED) {
                setConnectionStatus('disconnected');
            }
            eventSource.close();
        };

        // Очистка
        return () => {
            eventSource.close();
        };
    }, []);

    const selenium = serverState.seleniumStat;
    const playwright = serverState.playwrightStat;
    const activeSessions = selenium.sessions.length;

    const getStatusIndicatorClass = () => {
        switch (connectionStatus) {
            case 'connected':
                return 'connected';
            case 'disconnected':
                return 'disconnected';
            default:
                return 'connecting';
        }
    };

    return (
        <header className="header">
            <div className="logo">Jelenoid</div>
            <div className="status-panel">
                <div className="status-item">
                    <span className="status-label">Status</span>
                    <div className={`connection-indicator ${getStatusIndicatorClass()}`}>
                        <div className="indicator-dot"></div>
                        <span className="connection-text">{connectionStatus}</span>
                    </div>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Selenium Queue</span>
                    <span className="status-value">{selenium.queued}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Selenium Limit</span>
                    <span className="status-value">{activeSessions} / {selenium.total}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Playwright Queue</span>
                    <span className="status-value">{playwright.queuedPlaywrightSessionsSize}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Playwright Limit</span>
                    <span className="status-value">
                        {playwright.activePlaywrightSessionsSize} / {playwright.maxPlaywrightSessionsSize}
                    </span>
                </div>
            </div>
        </header>
    );
};

export default Header;
