import { useState, useEffect } from 'react';
// ИЗМЕНЕНИЕ ЗДЕСЬ: Добавляем 'type', так как StatusResponse - это только тип.
import type { StatusResponse } from '../types/api.types';

// Получаем базовый URL API из переменных окружения
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '';

export const useJelenoidApi = () => {
    const [status, setStatus] = useState<StatusResponse | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        // Функция для получения начального состояния
        const fetchInitialStatus = async () => {
            try {
                setIsLoading(true); // Устанавливаем загрузку в true перед запросом
                const response = await fetch(`${API_BASE_URL}/api/status`);
                if (!response.ok) {
                    throw new Error(`Failed to fetch status: ${response.statusText}`);
                }
                const data: StatusResponse = await response.json();
                setStatus(data);
            } catch (e: any) {
                setError(e.message);
            } finally {
                setIsLoading(false); // Устанавливаем в false после завершения
            }
        };

        fetchInitialStatus();

        // Подписываемся на живые обновления через SSE
        const eventSource = new EventSource(`${API_BASE_URL}/events`);

        eventSource.addEventListener('state-update', (event) => {
            try {
                const newStatus = JSON.parse(event.data);
                setStatus(newStatus);
            } catch (e) {
                console.error('Failed to parse SSE data:', e);
            }
        });

        eventSource.onerror = () => {
            setError('Connection to event stream failed. Please refresh.');
            eventSource.close();
        };

        // Закрываем соединение при размонтировании компонента
        return () => {
            eventSource.close();
        };
    }, []); // Пустой массив означает, что эффект выполнится один раз

    return { status, isLoading, error };
};
