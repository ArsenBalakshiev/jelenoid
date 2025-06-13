import React from 'react';
import type { Session } from '../types/api.types';
import { SessionCard } from './SessionCard';
import { Typography, Box } from '@mui/material';

interface SessionListProps {
    sessions: Session[];
}

export const SessionList: React.FC<SessionListProps> = ({ sessions }) => {
    return (
        <Box>
            <Typography variant="h6" gutterBottom>
                Активные сессии ({sessions.length})
            </Typography>
            {sessions.length > 0 ? (
                sessions.map(session => <SessionCard key={session.id} session={session} />)
            ) : (
                <Typography variant="body1" color="text.secondary">
                    Нет активных сессий. Запустите ваш тест, чтобы увидеть их здесь.
                </Typography>
            )}
        </Box>
    );
};
