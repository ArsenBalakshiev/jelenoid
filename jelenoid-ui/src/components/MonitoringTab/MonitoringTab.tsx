// src/components/MonitoringTab/MonitoringTab.tsx
import React, { useState, useEffect } from 'react';
import VncSessionCard   from './VncSessionCard';
import SessionLogsCard  from './SessionLogsCard';
import SessionCard      from './SessionCard';
import { SessionInfo }  from '../../types/server';
import './MonitoringTab.css';

interface Props {
    sessions:     SessionInfo[];
    setSessions: (s: SessionInfo[]) => void;
}

const MonitoringTab: React.FC<Props> = ({ sessions, setSessions }) => {
    const [selected, setSelected] = useState<SessionInfo | null>(null);

    /* -------- открыть (VNC+логи) -------- */
    const open = (s: SessionInfo) =>
        setSelected(sel => sel?.hubSessionId === s.hubSessionId ? null : s);

    /* -------- закрыть сессию на сервере -------- */
    const close = async (s: SessionInfo) => {
        await fetch(`${import.meta.env.VITE_SERVER_BASE_URL}/wd/hub/session/${s.hubSessionId}`, {
            method: 'DELETE',
        });
        setSessions(sessions.filter(x => x.hubSessionId !== s.hubSessionId));
        if (selected?.hubSessionId === s.hubSessionId) setSelected(null);
    };

    /* -------- АВТО-закрытие, если карточка пропала из списка -------- */
    useEffect(() => {
        if (selected && !sessions.some(x => x.hubSessionId === selected.hubSessionId)) {
            setSelected(null);                // ← убираем VNC и логи
        }
    }, [sessions, selected]);

    return (
        <div className="monitoring-layout">
            <div className="sessions-list"
                 style={{ overflowY: sessions.length > 3 ? 'auto' : 'hidden' }}
            >
                <h3>Активные сессии ({sessions.length})</h3>

                {sessions.map(s => (
                    <SessionCard
                        key={s.hubSessionId}
                        session={s}
                        active={selected?.hubSessionId === s.hubSessionId}
                        onOpen={open}
                        onClose={close}
                    />
                ))}
            </div>

            <div className="monitoring-side">
                {selected && (
                    <>
                        <VncSessionCard  sessionId={selected.hubSessionId} />
                        <SessionLogsCard sessionId={selected.hubSessionId} />
                    </>
                )}
            </div>
        </div>
    );
};

export default MonitoringTab;
