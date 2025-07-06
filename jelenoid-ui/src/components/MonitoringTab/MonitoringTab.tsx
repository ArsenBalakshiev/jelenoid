import React, { useState, useEffect } from 'react';
import VncSessionCard   from './VncSessionCard';
import SessionLogsCard  from './SessionLogsCard';
import SessionCard      from './SessionCard';
import { MonitoringSession, SeleniumMonitoringSession, PlaywrightMonitoringSession } from '../../types/server';
import './MonitoringTab.css';

// type guards
function isSeleniumSession(s: MonitoringSession): s is SeleniumMonitoringSession {
    return s.kind === 'selenium';
}
function isPlaywrightSession(s: MonitoringSession): s is PlaywrightMonitoringSession {
    return s.kind === 'playwright';
}

interface Props {
    sessions: MonitoringSession[];
}

const MonitoringTab: React.FC<Props> = ({ sessions }) => {
    // Для выделения только id selenium-сессии
    const [selectedId, setSelectedId] = useState<string | null>(null);

    const open = (s: MonitoringSession) => {
        if (isSeleniumSession(s))
            setSelectedId(id => id === s.hubSessionId ? null : s.hubSessionId);
    };

    const close = async (s: MonitoringSession) => {
        if (isSeleniumSession(s)) {
            await fetch(`${import.meta.env.VITE_SERVER_BASE_URL}/wd/hub/session/${s.hubSessionId}`, {
                method: 'DELETE',
            });
            // После удаления сервер сам пришлёт новое состояние через SSE!
        }
    };

    // Сбросить выделение, если выбранная сессия пропала
    useEffect(() => {
        if (
            selectedId &&
            !sessions.some(x => isSeleniumSession(x) && x.hubSessionId === selectedId)
        ) {
            setSelectedId(null);
        }
    }, [sessions, selectedId]);

    // Найти выбранную selenium-сессию (для мониторинга/логов)
    const selected = sessions.find(
        s => isSeleniumSession(s) && s.hubSessionId === selectedId
    ) as SeleniumMonitoringSession | undefined;

    return (
        <div className="monitoring-layout">
            <div className="sessions-list"
                 style={{ overflowY: sessions.length > 3 ? 'auto' : 'hidden' }}
            >
                <h3>Активные сессии ({sessions.length})</h3>
                {sessions.map(s => (
                    <SessionCard
                        key={isSeleniumSession(s) ? s.hubSessionId : s.clientSessionId}
                        session={s}
                        active={isSeleniumSession(s) && !!selected && selected.hubSessionId === s.hubSessionId}
                        onOpen={open}
                        onClose={close}
                    />
                ))}
            </div>
            <div className="monitoring-side">
                {selected && (
                    <>
                        {selected.vncEnabled && (
                            <VncSessionCard sessionId={selected.hubSessionId} />
                        )}
                        <SessionLogsCard sessionId={selected.hubSessionId} />
                    </>
                )}
            </div>
        </div>
    );
};

export default MonitoringTab;
