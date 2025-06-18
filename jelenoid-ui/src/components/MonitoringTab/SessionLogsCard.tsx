import React, { useEffect, useRef, useState } from 'react';
import './SessionLogsCard.css';

const SessionLogsCard: React.FC<{ sessionId: string }> = ({ sessionId }) => {
    const base = import.meta.env.VITE_SERVER_BASE_URL;
    const [lines, setLines] = useState<string[]>([]);
    const endRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        setLines([]);
        const es = new EventSource(`${base}/logs/${sessionId}`);
        es.onmessage = e => setLines(prev => [...prev, e.data]);
        return () => es.close();
    }, [sessionId, base]);

    useEffect(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }), [lines]);

    return (
        <div className="logs-card">
            <div className="logs-header">Логи контейнера</div>
            <div className="logs-body">
                {lines.map((l, i) => (
                    <div key={i} className="log-line">{l}</div>
                ))}
                <div ref={endRef} />
            </div>
        </div>
    );
};

export default SessionLogsCard;
