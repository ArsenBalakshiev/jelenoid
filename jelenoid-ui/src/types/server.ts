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

export interface SessionPainInfo {
    clientSessionId: string;
    clientSessionUrl: string;
    containerClientUrl: string;
    containerInfo: ContainerInfo;
}

export interface ContainerInfo {
    containerId: string;
    containerName: string;
    lastActivity: number;
}

export interface SeleniumStat {
    total: number;
    used: number;
    queued: number;
    inProgress: number;
    sessions: SessionInfo[];
    queuedRequests: QueuedRequest[];
}

export interface PlaywrightStat {
    maxPlaywrightSessionsSize: number;
    activePlaywrightSessionsSize: number;
    queuedPlaywrightSessionsSize: number;
    activePlaywrightSessions: SessionPainInfo[];
    queuedPlaywrightSessions: SessionPainInfo[];
}

export interface ServerState {
    seleniumStat: SeleniumStat;
    playwrightStat: PlaywrightStat;
}

export type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error';
