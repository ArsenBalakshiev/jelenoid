import React from 'react';
import { Paper, Typography } from '@mui/material';
import Grid2 from '@mui/material/Unstable_Grid2'; // Используем Grid2 для лучшего выравнивания

interface StatCardProps {
    title: string;
    value: number | string;
}

const StatCard: React.FC<StatCardProps> = ({ title, value }) => (
    <Paper elevation={3} sx={{ p: 2, display: 'flex', flexDirection: 'column', height: '100%' }}>
        <Typography color="text.secondary" gutterBottom>{title}</Typography>
        <Typography component="p" variant="h4">{value}</Typography>
    </Paper>
);

export const StatsPanel: React.FC<{ total: number; used: number; queued: number; inProgress: number }> =
    ({ total, used, queued, inProgress }) => {
        return (
            <Grid2 container spacing={3}>
                <Grid2 xs={6} sm={3}><StatCard title="Лимит" value={total} /></Grid2>
                <Grid2 xs={6} sm={3}><StatCard title="Используется" value={used} /></Grid2>
                <Grid2 xs={6} sm={3}><StatCard title="В процессе" value={inProgress} /></Grid2>
                <Grid2 xs={6} sm={3}><StatCard title="В очереди" value={queued} /></Grid2>
            </Grid2>
        );
    };
