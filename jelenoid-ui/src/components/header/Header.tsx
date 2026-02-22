import React from 'react';
import { ServerState } from '../../types/server';
import './Header.css';

interface HeaderProps {
    serverState: ServerState;
    connectionStatus: 'connecting' | 'connected' | 'disconnected'; // Добавили новый пропс
}

const Header: React.FC<HeaderProps> = ({ serverState, connectionStatus }) => {
    const selenium = serverState.seleniumStat;
    const playwright = serverState.playwrightStat;

    const activeSessions = Array.isArray(selenium.activeSeleniumSessions)
        ? selenium.activeSeleniumSessions.length
        : 0;

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
                    <span className="status-value">{selenium.queued ?? 0}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Selenium Limit</span>
                    <span className="status-value">{activeSessions} / {selenium.total ?? 0}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Playwright Queue</span>
                    <span className="status-value">{playwright.queuedPlaywrightSessionsSize ?? 0}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Playwright Limit</span>
                    <span className="status-value">
                        {playwright.activePlaywrightSessionsSize ?? 0} / {playwright.maxPlaywrightSessionsSize ?? 0}
                    </span>
                </div>
            </div>
        </header>
    );
};

export default Header;
