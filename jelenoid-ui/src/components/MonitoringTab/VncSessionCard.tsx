import React, { useEffect, useRef, useState } from 'react';
import VncScreen from './VncScreen';
import './VncSessionCard.css';

interface Props { sessionId: string }

const VncSessionCard: React.FC<Props> = ({ sessionId }) => {
    const base  = import.meta.env.VITE_SERVER_BASE_URL;
    const wsUrl = `${base.replace(/^http/, 'ws')}/vnc/${sessionId}`;

    const cardRef               = useRef<HTMLDivElement>(null);
    const [fullscreen, setFS]    = useState(false);
    const [connected,  setConn] = useState(false);

    /* fullscreen */
    useEffect(() => {
        const f = () => setFS(document.fullscreenElement === cardRef.current);
        document.addEventListener('fullscreenchange', f);
        return () => document.removeEventListener('fullscreenchange', f);
    }, []);

    const toFS = () => cardRef.current?.requestFullscreen?.();

    return (
        <div ref={cardRef} className="vnc-card">
            {!fullscreen &&
                <button className="vnc-fs-btn" onClick={toFS}>На&nbsp;весь&nbsp;экран</button>}
            {!connected &&
                <div className="vnc-overlay">Подключаюсь…</div>}

            <VncScreen
                url={wsUrl}
                viewOnly={false}
                onConnect={() => setConn(true)}
                onDisconnect={() => setConn(false)}
                onError={() => setConn(false)}
            />
        </div>
    );
};

export default VncSessionCard;
