import React from 'react';
import { useJelenoidApi } from '../hooks/useJelenoidApi';
import { StatsPanel } from './StatsPanel';
import { SessionList } from './SessionList';
import { Container, Grid, CircularProgress, Typography, Box } from '@mui/material';

export const Dashboard: React.FC = () => {
    const { status, isLoading, error } = useJelenoidApi();

    if (isLoading) {
        return (
            <Box display="flex" justifyContent="center" alignItems="center" minHeight="100vh">
                <CircularProgress />
            </Box>
        );
    }

    if (error) {
        return <Typography color="error" sx={{ p: 4 }}>Ошибка: {error}</Typography>;
    }

    if (!status) {
        return <Typography sx={{ p: 4 }}>Нет данных.</Typography>;
    }

    const pendingCount = status.inProgress - status.used;

    return (
        <Box component="main" sx={{ flexGrow: 1, py: 4, px: 2 }}>
            <Container maxWidth="xl">
                <Typography variant="h4" component="h1" gutterBottom>
                    Jelenoid Dashboard
                </Typography>
                <Grid container spacing={3}>
                    {/* Панель статистики */}
                    <Grid item xs={12}>
                        <StatsPanel total={status.total} used={status.used} queued={status.queued} inProgress={pendingCount}/>
                    </Grid>
                    {/* Левая колонка для основного контента */}
                    <Grid item xs={12} lg={8}>
                        <SessionList sessions={status.sessions} />
                    </Grid>
                    {/* Правая колонка для дополнительного контента */}
                    <Grid item xs={12} lg={4}>
                        {/* TODO: Здесь будет компонент для отображения очереди */}
                        <Typography variant="h6">В очереди ({status.queued})</Typography>
                    </Grid>
                </Grid>
            </Container>
        </Box>
    );
};
