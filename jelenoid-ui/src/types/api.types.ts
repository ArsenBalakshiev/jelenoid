/**
 * Описывает информацию об одной активной сессии, как ее отдает /status
 */
export interface Session {
    id: string;
    vnc: boolean;
    browser: string;
    version: string;
    startTime: string; // ISO 8601 строка, например "2025-06-11T20:30:00Z"
}

/**
 * Описывает информацию об одном запросе в очереди
 */
export interface QueuedRequestInfo {
    browser: string;
    version: string;
    queuedTime: string; // ISO 8601 строка
}

/**
 * Описывает полную структуру ответа от эндпоинта GET /status
 */
export interface StatusResponse {
    total: number;
    used: number;
    queued: number;
    inProgress: number;
    sessions: Session[];
    queuedRequests: QueuedRequestInfo[];
}