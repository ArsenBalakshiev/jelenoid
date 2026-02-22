
export interface ContainerInfo {
    containerId: string;
    containerName: string;
    lastActivity: number;
    startTime: string;
}

export interface SeleniumSession {
    hubSessionId: string;
    remoteSessionId: string;
    containerInfo: ContainerInfo;
    browserName: string;
    version: string;
    vncEnabled: boolean;
    sessionInfo: any;
    lastActivity: number;
    startTime: string;
}

export interface QueuedRequestInfo {
    requestId?: string;
    [key: string]: any;
}

export interface SeleniumStat {
    total: number;
    used: number;
    queued: number;
    inProgress: number;
    activeSeleniumSessions: SeleniumSession[];
    queuedSeleniumSession: QueuedRequestInfo[];
}

export interface SessionPairInfo {
    clientSessionId: string;
    clientSessionUrl: string;
    containerClientUrl: string;
    playwrightVersion: string;
    containerInfo: ContainerInfo;
}

export interface PlaywrightStat {
    maxPlaywrightSessionsSize: number;
    activePlaywrightSessionsSize: number;
    queuedPlaywrightSessionsSize: number;
    activePlaywrightSessions: SessionPairInfo[];
    queuedPlaywrightSessions: SessionPairInfo[];
}

export interface ServerState {
    seleniumStat: SeleniumStat;
    playwrightStat: PlaywrightStat;
}

export interface SeleniumMonitoringSession extends SeleniumSession {
    kind: 'selenium';
}

export interface PlaywrightMonitoringSession extends SessionPairInfo {
    kind: 'playwright';
}

export type MonitoringSession = SeleniumMonitoringSession | PlaywrightMonitoringSession;
