// src/components/MonitoringTab/VncScreen.tsx
import React, { useEffect, useRef } from 'react';
import RFB from '@novnc/novnc/core/rfb';

interface Props {
    url: string;
    viewOnly?: boolean;
    onConnect?: () => void;
    onDisconnect?: () => void;
    onError?: (e: unknown) => void;
}
const PASS = 'selenoid';

const VncScreen: React.FC<Props> = ({
                                        url, viewOnly = false, onConnect, onDisconnect, onError,
                                    }) => {
    const wrap = useRef<HTMLDivElement>(null);
    const rfb  = useRef<RFB>();

    // универсальный ресайз
    const rescale = () => {
        if (rfb.current && typeof (rfb.current as any)._rescale === 'function') {
            // приватный метод noVNC, но официального API пока нет
            (rfb.current as any)._rescale();
        }
    };

    useEffect(() => {
        if (!wrap.current) return;

        const obj = new RFB(wrap.current, url, {
            wsProtocols: ['binary'],
            shared: true,
            credentials: { password: PASS },
        });
        obj.viewOnly      = viewOnly;
        obj.scaleViewport = true;
        obj.background    = '#000';
        rfb.current       = obj;

        obj.addEventListener('connect', () => {
            onConnect?.();
            requestAnimationFrame(rescale);      // сразу после connect
        });
        obj.addEventListener('credentialsrequired', () =>
            obj.sendCredentials({ password: PASS })
        );
        obj.addEventListener('disconnect',     () => onDisconnect?.());
        obj.addEventListener('securityfailure',e => onError?.(e));

        // любое изменение размеров контейнера
        const ro = new ResizeObserver(() => requestAnimationFrame(rescale));
        ro.observe(wrap.current);

        // ресайз и при выходе из fullscreen
        const fs = () => requestAnimationFrame(rescale);
        document.addEventListener('fullscreenchange', fs);

        return () => {
            document.removeEventListener('fullscreenchange', fs);
            ro.disconnect();
            obj.disconnect();
        };
    }, [url, viewOnly]);

    return <div ref={wrap} className="vnc-viewport"/>;
};

export default VncScreen;
