import React, { useEffect, useRef } from 'react';
import RFB from '@novnc/novnc/core/rfb';

export const DEFAULT_VNC_PASSWORD = 'selenoid';

interface Props {
    url: string;
    password?: string;
    viewOnly?: boolean;
    onConnect?: () => void;
    onDisconnect?: () => void;
    onError?: (e: unknown) => void;
}

const VncScreen: React.FC<Props> = ({
                                        url, password = DEFAULT_VNC_PASSWORD, viewOnly = false,
                                        onConnect, onDisconnect, onError,
                                    }) => {
    const wrap = useRef<HTMLDivElement>(null);
    const rfb  = useRef<RFB>();

    const rescale = () => {
        if (rfb.current && typeof (rfb.current as any)._rescale === 'function') {
            (rfb.current as any)._rescale();
        }
    };

    useEffect(() => {
        if (!wrap.current) return;

        const obj = new RFB(wrap.current, url, {
            wsProtocols: ['binary'],
            shared: true,
            credentials: { password },
        });
        obj.viewOnly      = viewOnly;
        obj.scaleViewport = true;
        obj.background    = '#000';
        rfb.current       = obj;

        obj.addEventListener('connect', () => {
            onConnect?.();
            requestAnimationFrame(rescale);
        });
        obj.addEventListener('credentialsrequired', () =>
            obj.sendCredentials({ password })
        );
        obj.addEventListener('disconnect',     () => onDisconnect?.());
        obj.addEventListener('securityfailure',e => onError?.(e));

        const ro = new ResizeObserver(() => requestAnimationFrame(rescale));
        ro.observe(wrap.current);

        const fs = () => requestAnimationFrame(rescale);
        document.addEventListener('fullscreenchange', fs);

        return () => {
            document.removeEventListener('fullscreenchange', fs);
            ro.disconnect();
            obj.disconnect();
        };
    }, [url, password, viewOnly, onConnect, onDisconnect, onError]);

    return <div ref={wrap} className="vnc-viewport"/>;
};

export default VncScreen;
