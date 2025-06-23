import React, { useState, useEffect, useMemo } from 'react';
import './ManualSessionTab.css';

// Типизация для информации о браузере с бэкенда
interface BrowserInfo {
    name: string;
    version: string;
    dockerImageName: string;
    isDefault: boolean;
}

const ManualSessionTab: React.FC = () => {
    // Состояние для хранения списка всех доступных браузеров
    const [browsers, setBrowsers] = useState<BrowserInfo[]>([]);

    // Состояние для полей формы
    const [selectedBrowser, setSelectedBrowser] = useState<string>('');
    const [selectedVersion, setSelectedVersion] = useState<string>('');
    const [enableVNC, setEnableVNC] = useState<boolean>(true);

    // Состояния для обратной связи с пользователем
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);

    // 1. Получаем список браузеров при первом рендере компонента
    useEffect(() => {
        const fetchBrowsers = async () => {
            try {
                // Используем переменную окружения для базового URL
                const response = await fetch(`${import.meta.env.VITE_SERVER_BASE_URL}/api/browsers`);
                if (!response.ok) {
                    throw new Error('Failed to fetch browser list');
                }
                const data: BrowserInfo[] = await response.json();
                setBrowsers(data);

                // Устанавливаем браузер по умолчанию, если он есть
                const defaultBrowser = data.find(b => b.isDefault);
                if (defaultBrowser) {
                    setSelectedBrowser(defaultBrowser.name);
                    setSelectedVersion(defaultBrowser.version);
                } else if (data.length > 0) {
                    // Или просто первый из списка
                    setSelectedBrowser(data[0].name);
                    setSelectedVersion(data[0].version);
                }
            } catch (err: any) {
                setError(err.message || 'An error occurred while fetching browsers.');
            }
        };

        fetchBrowsers();
    }, []); // Пустой массив зависимостей означает, что эффект выполнится один раз

    // 2. Логика для динамических выпадающих списков
    const uniqueBrowserNames = useMemo(() => {
        return [...new Set(browsers.map(b => b.name))];
    }, [browsers]);

    const availableVersions = useMemo(() => {
        return browsers
            .filter(b => b.name === selectedBrowser)
            .map(b => b.version);
    }, [browsers, selectedBrowser]);

    // 3. Обработчик отправки формы
    const handleSubmit = async (event: React.FormEvent) => {
        event.preventDefault();
        setIsLoading(true);
        setError(null);

        // Формируем тело запроса в точности как в вашем примере
        const requestBody = {
            capabilities: {
                firstMatch: [{
                    browserName: selectedBrowser,
                    browserVersion: selectedVersion,
                    "goog:chromeOptions": { // Стандартные опции для Chrome
                        args: [
                            "--remote-allow-origins=*",
                            "--no-sandbox",
                            "--window-size=1920,1080",
                            "--disable-dev-shm-usage",
                            "--disable-gpu"
                        ],
                        extensions: []
                    },
                    "selenoid:options": { // Опции для VNC
                        enableVNC: enableVNC
                    }
                }]
            }
        };

        try {
            const response = await fetch(`${import.meta.env.VITE_SERVER_BASE_URL}/wd/hub/session`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(requestBody),
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.message || 'Failed to create session');
            }

            // В идеале здесь можно показать уведомление об успехе
            // или перенаправить пользователя на вкладку Monitoring
            alert('Session created successfully!');

        } catch (err: any) {
            setError(err.message || 'An error occurred.');
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="manual-session-tab">
            <h2>Create Manual Session</h2>
            <form className="session-form" onSubmit={handleSubmit}>
                <div className="form-row">
                    <div className="form-group">
                        <label htmlFor="browser-select">Browser</label>
                        <select
                            id="browser-select"
                            value={selectedBrowser}
                            onChange={(e) => {
                                setSelectedBrowser(e.target.value);
                                // При смене браузера сбрасываем версию на первую доступную
                                const firstVersion = browsers.find(b => b.name === e.target.value)?.version || '';
                                setSelectedVersion(firstVersion);
                            }}
                            disabled={isLoading || browsers.length === 0}
                        >
                            {uniqueBrowserNames.map(name => (
                                <option key={name} value={name}>{name}</option>
                            ))}
                        </select>
                    </div>

                    <div className="form-group">
                        <label htmlFor="version-select">Version</label>
                        <select
                            id="version-select"
                            value={selectedVersion}
                            onChange={(e) => setSelectedVersion(e.target.value)}
                            disabled={isLoading || availableVersions.length === 0}
                        >
                            {availableVersions.map(version => (
                                <option key={version} value={version}>{version}</option>
                            ))}
                        </select>
                    </div>
                </div>

                <div className="form-group checkbox-container"> {/* Обертка для позиционирования */}
                    <input
                        type="checkbox"
                        id="vnc-checkbox" // Добавляем ID для связи
                        className="custom-checkbox-input"
                        checked={enableVNC}
                        onChange={(e) => setEnableVNC(e.target.checked)}
                        disabled={isLoading}
                    />
                    <label
                        htmlFor="vnc-checkbox" // Связываем с input по ID
                        className="checkbox-label"
                    >
                        Enable VNC
                    </label>
                </div>

                <button type="submit" className="submit-btn" disabled={isLoading}>
                    {isLoading ? 'Creating...' : 'Create Session'}
                </button>

                {error && <p className="error-message">{error}</p>}
            </form>
        </div>
    );
};

export default ManualSessionTab;
