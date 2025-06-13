import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
    palette: {
        mode: 'dark', // Включаем темную тему, как у вас сейчас
        primary: {
            main: '#90caf9', // Светло-голубой для акцентов
        },
        secondary: {
            main: '#f48fb1', // Розовый для других акцентов
        },
        background: {
            default: '#121212', // Глубокий темный фон
            paper: '#1e1e1e',   // Цвет для "бумажных" элементов, как карточки
        },
        text: {
            primary: '#e0e0e0',
            secondary: '#b0b0b0',
        },
    },
});
