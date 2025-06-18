import React from 'react';
import { SessionInfo } from '../../types/server';
import './SessionCard.css';

interface SessionCardProps {
    session: SessionInfo;
}

const SessionCard: React.FC<SessionCardProps> = ({ session }) => {
    const {
        hubSessionId,
        browserName,
        browserVersion,
        vncEnabled,
        containerInfo,
        lastActivity,
        startTime,
    } = session;

    const lastActivityDate = new Date(lastActivity || containerInfo?.lastActivity);
    const startTimeDate = new Date(startTime || containerInfo?.startTime);

    return (
        <div className="session-card">
            <div className="session-header">
                <span className="browser">{browserName} {browserVersion}</span>
                {vncEnabled && <span className="vnc-badge">VNC</span>}
            </div>
            <div className="session-info">
                <div><b>hubSessionId:</b> <span className="mono">{hubSessionId}</span></div>
                <div><b>container:</b> <span className="mono">{containerInfo?.containerName}</span></div>
                <div><b>Start:</b> {startTimeDate.toLocaleString()}</div>
                <div><b>Last activity:</b> {lastActivityDate.toLocaleString()}</div>
            </div>
        </div>
    );
};

export default SessionCard;
