import { Dashboard } from './components/Dashboard';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { theme } from './theme';

function App() {
    return (
        // ThemeProvider применяет нашу кастомную тему ко всем дочерним компонентам
        <ThemeProvider theme={theme}>
            {/* CssBaseline сбрасывает стили браузера и применяет базовые цвета темы */}
            <CssBaseline />
            <Dashboard />
        </ThemeProvider>
    );
}

export default App;
