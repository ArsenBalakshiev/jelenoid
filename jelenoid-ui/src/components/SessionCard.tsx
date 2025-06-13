import React, { useState, useEffect } from 'react';
import type { Session } from '../types/api.types';
import { Card, CardContent, Typography, Box, Chip, IconButton, Tooltip, CircularProgress } from '@mui/material';
import { ScreenShare, Description, DeleteForever } from '@mui/icons-material';

export const SessionCard: React.FC<{ session: Session }> = ({ session }) => {
    const [uptime, setUptime] = useState('');
    const [isDeleting, setIsDeleting] = useState(false);

    useEffect(() => {
        const calculateUptime = () => {
            const seconds = Math.round((Date.now() - new Date(session.startTime).getTime()) / 1000);
            const m = Math.floor(seconds / 60);
            const s = seconds % 60;
            setUptime(`${m}м ${s}с`);
        };
        calculateUptime();
        const intervalId = setInterval(calculateUptime, 1000);
        return () => clearInterval(intervalId);
    }, [session.startTime]);

    const handleDelete = async () => {
        if (window.confirm(`Вы уверены, что хотите завершить сессию ${session.id}?`)) {
            setIsDeleting(true);
            try {
                await fetch(`/wd/hub/session/${session.id}`, { method: 'DELETE' });
            } catch (error) {
                alert('Ошибка при удалении сессии.');
            } finally {
                setIsDeleting(false);
            }
        }
    };

    return (
        <Card variant="outlined" sx={{
            mb: 2,
            transition: 'box-shadow 0.3s, border-color 0.3s',
            '&:hover': {
                boxShadow: '0 4px 20px 0 rgba(0,0,0,0.1)',
                borderColor: 'primary.main'
            }
        }}>
            <CardContent>
                <Box display="flex" justifyContent="space-between" alignItems="center">
                    <Chip label={`${session.browser} ${session.version}`} color="primary" />
                    <Typography variant="caption" color="text.secondary">
                        Uptime: {uptime}
                    </Typography>
                </Box>
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1, wordBreak: 'break-all' }}>
                    ID: {session.id}
                </Typography>
                <Box sx={{ mt: 1, display: 'flex', justifyContent: 'flex-end' }}>
                    <Tooltip title="Открыть VNC">
            <span>
              <IconButton href={`/vnc/${session.id}`} target="_blank" disabled={!session.vnc}>
                {/* ИЗМЕНЕНИЕ ЗДЕСЬ: Используем правильную иконку */}
                  <ScreenShare />
              </IconButton>
            </span>
                    </Tooltip>
                    <Tooltip title="Посмотреть логи">
                        <IconButton><Description /></IconButton>
                    </Tooltip>
                    <Tooltip title="Удалить сессию">
                        <IconButton onClick={handleDelete} disabled={isDeleting} color="error">
                            {isDeleting ? <CircularProgress size={24} color="inherit" /> : <DeleteForever />}
                        </IconButton>
                    </Tooltip>
                </Box>
            </CardContent>
        </Card>
    );
};
