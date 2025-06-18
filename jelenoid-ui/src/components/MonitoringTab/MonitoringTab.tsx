import React, { useState, useEffect } from 'react';
import VncSessionCard from './VncSessionCard';
import SessionLogsCard from './SessionLogsCard';   // ← ваш компонент логов
import SessionCard from './SessionCard';           // ← карточка в списке
import { SessionInfo } from '../../types/server';
import './MonitoringTab.css';

interface Props { sessions: SessionInfo[] }

const MonitoringTab: React.FC<Props> = ({ sessions }) => {
    const [selected, setSelected] = useState<SessionInfo | null>(null);

    const handleClick = (s: SessionInfo) =>
        setSelected(sel => sel?.hubSessionId === s.hubSessionId ? null : s);

    /* если сессия исчезла со стороны backend — закрываем карточки */
    useEffect(() => {
        if (selected && !sessions.some(x => x.hubSessionId === selected.hubSessionId)) {
            setSelected(null);
        }
    }, [sessions, selected]);

    return (
        <div className="monitoring-layout">
            <div className="sessions-list">
                <h3>Активные сессии ({sessions.length})</h3>
                {sessions.map(s => (
                    <div key={s.hubSessionId} onClick={() => handleClick(s)}>
                        <SessionCard session={s} active={selected?.hubSessionId === s.hubSessionId}/>
                    </div>
                ))}
            </div>

            <div className="monitoring-side">
                {selected && (
                    <>
                        <VncSessionCard sessionId={selected.hubSessionId}/>
                        <SessionLogsCard sessionId={selected.hubSessionId}/>
                    </>
                )}
            </div>
        </div>
    );
};

export default MonitoringTab;
