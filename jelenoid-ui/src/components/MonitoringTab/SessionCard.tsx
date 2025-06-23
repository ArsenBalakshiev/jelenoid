import React from 'react';
import { SessionInfo } from '../../types/server';
import './SessionCard.css';

interface Props {
    session: SessionInfo;
    active: boolean;
    onOpen:   (s: SessionInfo) => void;
    onClose:  (s: SessionInfo) => void;
}

const SessionCard: React.FC<Props> = ({ session, active, onOpen, onClose }) => (
    <div className={`session-card ${active ? 'active' : ''}`}>
        <div className="session-header">
      <span className="browser">
        {session.browserName} {session.browserVersion}
      </span>
            {session.vncEnabled && <span className="vnc-badge">VNC</span>}
        </div>

        <div className="session-info">
            <div><b>hubSessionId:</b> <span className="mono">{session.hubSessionId}</span></div>
            <div><b>container:</b>    <span className="mono">{session.containerInfo.containerName}</span></div>
        </div>

        <div className="session-actions">
            <button onClick={() => onOpen(session)}>Мониторинг</button>
            <button className="danger" onClick={() => onClose(session)}>Закрыть сессию</button>
        </div>
    </div>
);

export default SessionCard;
