import React, { useState, useEffect } from 'react';
import { ServerState } from '../../types/server';
import './Header.css';

const Header: React.FC = () => {
    const [serverState, setServerState] = useState<ServerState>({
        total: 0,
        used: 0,
        queued: 0,
        inProgress: 0,
        sessions: [],
        queuedRequests: [],
    });

    // Используем три состояния: 'connecting', 'connected', 'disconnected'
    const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');

    useEffect(() => {
        // Устанавливаем начальный статус при создании компонента
        setConnectionStatus('connecting');
        const eventSource = new EventSource('/events');

        // Срабатывает только при успешном установлении соединения
        eventSource.onopen = () => {
            setConnectionStatus('connected');
        };

        // Ключевой обработчик: срабатывает при ошибке подключения
        eventSource.onerror = () => {
            // readyState переходит в CLOSED (2) при невозможности подключиться
            if (eventSource.readyState === EventSource.CLOSED) {
                setConnectionStatus('disconnected');
            }
            // Соединение будет закрыто браузером, и он будет пытаться переподключиться
            eventSource.close();
        };

        // Обработка входящих данных
        const handleStateUpdate = (event: MessageEvent) => {
            try {
                const data = JSON.parse(event.data);
                setServerState(data);
                // Если пришли данные, значит соединение точно есть
                if (connectionStatus !== 'connected') {
                    setConnectionStatus('connected');
                }
            } catch (error) {
                console.error('Ошибка парсинга данных SSE:', error);
            }
        };

        eventSource.addEventListener('state-update', handleStateUpdate);
        eventSource.addEventListener('message', handleStateUpdate);

        // Очистка при размонтировании компонента
        return () => {
            eventSource.removeEventListener('state-update', handleStateUpdate);
            eventSource.removeEventListener('message', handleStateUpdate);
            eventSource.close();
        };
    }, []); // Пустой массив зависимостей для выполнения один раз

    const activeSessions = serverState.sessions.length;

    const getStatusIndicatorClass = () => {
        switch (connectionStatus) {
            case 'connected':
                return 'connected';
            case 'disconnected':
                return 'disconnected';
            default:
                return 'connecting';
        }
    };

    return (
        <header className="header">
            <div className="logo">
                Jelenoid
            </div>
            <div className="status-panel">
                <div className="status-item">
                    <span className="status-label">Status</span>
                    <div className={`connection-indicator ${getStatusIndicatorClass()}`}>
                        <div className="indicator-dot"></div>
                        <span className="connection-text">{connectionStatus}</span>
                    </div>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Queue</span>
                    <span className="status-value">{serverState.queued}</span>
                </div>
                <div className="status-divider"></div>
                <div className="status-item">
                    <span className="status-label">Limit</span>
                    <span className="status-value">{activeSessions} / {serverState.total}</span>
                </div>
            </div>
        </header>
    );
};

export default Header;

