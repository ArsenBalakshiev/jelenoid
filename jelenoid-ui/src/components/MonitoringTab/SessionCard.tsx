import React from 'react';
import { MonitoringSession } from '../../types/server';
import './SessionCard.css';

interface Props {
    session: MonitoringSession;
    active: boolean;
    onOpen: (s: MonitoringSession) => void;
    onClose: (s: MonitoringSession) => void;
}

const logos: Record<string, string> = {
    selenium: '/assets/selenium_logo.png',      // или внешняя ссылка
    playwright: '/assets/playwright_logo.png'   // или внешняя ссылка
};

const SessionCard: React.FC<Props> = ({ session, active, onOpen, onClose }) => {
    const containerName = session.containerInfo?.containerName || '—';
    const logoSrc = logos[session.kind];
    const logoAlt = session.kind === 'selenium' ? 'Selenium' : 'Playwright';

    return (
        <div className={`session-card ${active ? 'active' : ''}`}>
            <div className="session-header">
                <img
                    src={logoSrc}
                    alt={logoAlt}
                    className="session-logo"
                />
                <span className="browser">
                    {session.kind === 'selenium'
                        ? `${session.browserName} ${session.browserVersion}`
                        : `${session.playwrightVersion}`}
                </span>
                {session.kind === 'selenium' && session.vncEnabled && (
                    <span className="vnc-badge">VNC</span>
                )}
            </div>
            <div className="session-info">
                {session.kind === 'selenium' ? (
                    <>
                        <div><b>hubSessionId:</b> <span className="mono">{session.hubSessionId}</span></div>
                        <div><b>container:</b> <span className="mono">{containerName}</span></div>
                    </>
                ) : (
                    <>
                        <div><b>clientSessionId:</b> <span className="mono">{session.clientSessionId}</span></div>
                        <div><b>container:</b> <span className="mono">{containerName}</span></div>
                    </>
                )}
            </div>
            <div className="session-actions">
                {session.kind === 'selenium' ? (
                    <>
                        <button onClick={() => onOpen(session)}>Мониторинг</button>
                        <button className="danger" onClick={() => onClose(session)}>Закрыть сессию</button>
                    </>
                ) : (
                    <span style={{ color: '#888', fontSize: 14 }}>Нет мониторинга</span>
                )}
            </div>
        </div>
    );
};

export default SessionCard;
