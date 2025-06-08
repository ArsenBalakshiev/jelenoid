package com.balakshievas.superselenoid.service;

import com.balakshievas.superselenoid.config.BrowserProperties;
import com.balakshievas.superselenoid.dto.BrowserInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BrowserManagerService {

    private final Map<String, BrowserInfo> defaultBrowsers = new ConcurrentHashMap<>();
    private final Map<String, BrowserInfo> browserList = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(BrowserManagerService.class);

    @Autowired
    private BrowserProperties browserProperties;

    @Autowired
    private ResourceLoader resourceLoader;

    @PostConstruct
    public void initBrowsersFromFile() {
        String configPath = browserProperties.getConfigDir();
        String resourcePath;

        // Если путь не указан в конфигурации, используем путь по умолчанию из classpath
        if (configPath == null || configPath.isBlank()) {
            resourcePath = "classpath:browsers.json";
            log.info("Свойство 'superselenoid.browsers.config-dir' не установлено. Используется файл по умолчанию: {}", resourcePath);
        } else {
            // Если путь указан, формируем его с префиксом 'file:' для загрузки из файловой системы.
            // Заменяем обратные слэши для совместимости с Windows.
            resourcePath = "file:" + configPath.replace("\\", "/") + "/browsers.json";
            log.info("Загрузка конфигурации браузеров из указанной директории: {}", resourcePath);
        }

        Resource browsersConfigFile = resourceLoader.getResource(resourcePath);

        if (!browsersConfigFile.exists()) {
            log.warn("Конфигурационный файл browsers.json не найден по пути: {}. Браузеры не будут преднастроены.", resourcePath);
            return;
        }

        try (InputStream inputStream = browsersConfigFile.getInputStream()) {
            TypeReference<Map<String, Map<String, Object>>> typeRef = new TypeReference<>() {};
            Map<String, Map<String, Object>> browsersData = objectMapper.readValue(inputStream, typeRef);

            // ... остальная логика метода остается без изменений
            for (Map.Entry<String, Map<String, Object>> browserEntry : browsersData.entrySet()) {
                String browserName = browserEntry.getKey();
                Map<String, Object> browserDetails = browserEntry.getValue();

                String defaultVersion = (String) browserDetails.get("default");
                Map<String, Map<String, String>> versions = (Map<String, Map<String, String>>) browserDetails.get("versions");

                for (Map.Entry<String, Map<String, String>> versionEntry : versions.entrySet()) {
                    String version = versionEntry.getKey();
                    String dockerImage = versionEntry.getValue().get("image");
                    boolean isDefault = version.equals(defaultVersion);

                    BrowserInfo browserInfo = new BrowserInfo(browserName, version, dockerImage, isDefault);
                    addBrowser(browserInfo);
                }
            }
            log.info("Успешно загружено {} конфигураций браузеров из {}", browserList.size(), resourcePath);
        } catch (Exception e) {
            log.error("Не удалось прочитать или обработать {}. Проверьте формат файла и права доступа.", resourcePath, e);
        }
    }

    public BrowserInfo setDefaultBrowser(BrowserInfo browserInfo) {
        return defaultBrowsers.put(browserInfo.getName(), browserInfo);
    }

    public BrowserInfo addBrowser(BrowserInfo browserInfo) {
        if(browserInfo.getIsDefault()) {
            setDefaultBrowser(browserInfo);
        }
        return browserList.put(getBrowserKey(browserInfo.getName(), browserInfo.getVersion()), browserInfo);
    }

    public List<BrowserInfo> getAllBrowsers() {
        return new ArrayList<>(browserList.values());
    }

    public String getImageByBrowserNameAndVersion(String browserName, String version) {
        BrowserInfo browserInfo = browserList.get(getBrowserKey(browserName, version));
        if (browserInfo == null) {
            return defaultBrowsers.get(browserName).getDockerImageName();
        } else {
            return browserInfo.getDockerImageName();
        }
    }

    protected String getBrowserKey(String browserName, String version) {
        return browserName + ":" + version;
    }
}
