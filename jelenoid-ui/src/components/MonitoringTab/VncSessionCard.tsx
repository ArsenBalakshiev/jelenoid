import React, { useCallback, useEffect, useRef, useState } from 'react';
import VncScreen, { DEFAULT_VNC_PASSWORD } from './VncScreen';
import './VncSessionCard.css';

interface Props { sessionId: string }

const VncSessionCard: React.FC<Props> = ({ sessionId }) => {
    const base  = import.meta.env.VITE_SERVER_BASE_URL;
    const wsUrl = `${base.replace(/^http/, 'ws')}/vnc/${sessionId}`;

    const cardRef               = useRef<HTMLDivElement>(null);
    const [fullscreen, setFS]    = useState(false);
    const [connected,  setConn] = useState(false);
    const [password,   setPwd]  = useState(() => {
        return sessionStorage.getItem(`vnc-pwd-${sessionId}`) || DEFAULT_VNC_PASSWORD;
    });
    const [isDefault,  setDefault] = useState(password === DEFAULT_VNC_PASSWORD);

    const handleConnect    = useCallback(() => setConn(true), []);
    const handleDisconnect = useCallback(() => setConn(false), []);
    const handleError      = useCallback(() => setConn(false), []);

    useEffect(() => {
        sessionStorage.setItem(`vnc-pwd-${sessionId}`, password);
        setDefault(password === DEFAULT_VNC_PASSWORD);
    }, [password, sessionId]);

    useEffect(() => {
        const f = () => setFS(document.fullscreenElement === cardRef.current);
        document.addEventListener('fullscreenchange', f);
        return () => document.removeEventListener('fullscreenchange', f);
    }, []);

    const toFS = () => cardRef.current?.requestFullscreen?.();

    const resetPassword = () => {
        setPwd(DEFAULT_VNC_PASSWORD);
    };

    return (
        <div ref={cardRef} className="vnc-card">
            {!fullscreen && (
                <>
                    <button className="vnc-fs-btn" onClick={toFS}>На&nbsp;весь&nbsp;экран</button>
                    <div className="vnc-password-control">
                        <label className="vnc-password-label">
                            VNC&nbsp;пароль:
                            <input
                                type="password"
                                className="vnc-password-input"
                                value={password}
                                onChange={(e) => setPwd(e.target.value)}
                                placeholder={DEFAULT_VNC_PASSWORD}
                                spellCheck={false}
                                autoComplete="off"
                            />
                        </label>
                        {!isDefault && (
                            <button
                                type="button"
                                className="vnc-password-reset"
                                onClick={resetPassword}
                            >
                                Сброс
                            </button>
                        )}
                    </div>
                </>
            )}
            {!connected &&
                <div className="vnc-overlay">Подключаюсь…</div>}

            <VncScreen
                url={wsUrl}
                password={password}
                viewOnly={false}
                onConnect={handleConnect}
                onDisconnect={handleDisconnect}
                onError={handleError}
            />
        </div>
    );
};

export default VncSessionCard;
