export interface ContainerInfo {
    containerId: string;
    containerName: string;
    lastActivity: number;
    startTime: string;
}

export interface SessionInfo {
    hubSessionId: string;
    remoteSessionId: string;
    containerInfo: ContainerInfo;
    browserName: string;
    browserVersion: string;
    vncEnabled: boolean;
    lastActivity: number;
    startTime: string;
}

export interface QueuedRequest {
    // Добавьте поля в зависимости от структуры очереди
    [key: string]: any;
}

export interface ServerState {
    total: number;
    used: number;
    queued: number;
    inProgress: number;
    sessions: SessionInfo[];
    queuedRequests: QueuedRequest[];
}

export type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error';
