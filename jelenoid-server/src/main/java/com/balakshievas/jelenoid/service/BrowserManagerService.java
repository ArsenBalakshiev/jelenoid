package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.config.BrowserProperties;
import com.balakshievas.jelenoid.dto.BrowserInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BrowserManagerService {

    private final Object lock = new Object();

    private static final Logger log = LoggerFactory.getLogger(BrowserManagerService.class);

    private static final Map<String, BrowserInfo> defaultBrowsers = new ConcurrentHashMap<>();
    private static final Map<String, BrowserInfo> browserList = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private BrowserProperties browserProperties;

    @Autowired
    private ResourceLoader resourceLoader;

    @PostConstruct
    public void initBrowsersFromFile() {
        String configPath = browserProperties.getConfigDir();
        String resourcePath = "file:" + configPath.replace("\\", "/");
        log.info("Загрузка конфигурации браузеров из указанной директории: {}", resourcePath);

        Resource browsersConfigFile = resourceLoader.getResource(resourcePath);

        if (!browsersConfigFile.exists()) {
            String fileSystemPath = resourcePath.replace("file:", "");
            File file = new File(fileSystemPath);
            File parentDir = file.getParentFile();
            try {
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                if (!file.exists()) {
                    file.createNewFile();
                    Files.write(Paths.get(fileSystemPath), "{}".getBytes());
                    log.info("Создан новый конфигурационный файл browsers.json по пути: {}", fileSystemPath);
                }
            } catch (Exception e) {
                log.error("Не удалось создать файл browsers.json по пути: {}", fileSystemPath, e);
                return;
            }
        }

        try {
            List<BrowserInfo> browsers = readBrowsersFromFile();
            for (BrowserInfo browser : browsers) {
                addBrowserClearly(browser);
            }
        } catch (Exception e) {
            log.error("Ошибка при чтении или записи файла конфигурации браузеров", e);
        }
    }

    public BrowserInfo setDefaultBrowser(BrowserInfo browserInfo) {
        return defaultBrowsers.put(browserInfo.getName(), browserInfo);
    }

    public BrowserInfo addBrowserClearly(BrowserInfo browserInfo) {
        synchronized (lock) {
            if (browserInfo.getIsDefault()) {
                setDefaultBrowser(browserInfo);
            }
            return browserList.put(getBrowserKey(browserInfo.getName(), browserInfo.getVersion()), browserInfo);}
    }

    public BrowserInfo addBrowser(BrowserInfo browserInfo) {
        synchronized (lock) {
            if (browserInfo.getIsDefault()) {
                setDefaultBrowser(browserInfo);
            }
            BrowserInfo result = browserList.put(getBrowserKey(browserInfo.getName(),
                    browserInfo.getVersion()), browserInfo);
            try {
                writeBrowsersToFile(new ArrayList<>(browserList.values()));
            } catch (Exception e) {
                log.error("Ошибка при записи файла после добавления браузера", e);
            }
            return result;}
    }

    public BrowserInfo deleteBrowser(String browserName, String browserVersion) {
        synchronized (lock) {
            String key = getBrowserKey(browserName, browserVersion);
            defaultBrowsers.remove(key);
            BrowserInfo result = browserList.remove(key);
            try {
                writeBrowsersToFile(new ArrayList<>(browserList.values()));
            } catch (Exception e) {
                log.error("Ошибка при записи файла после удаления браузера", e);
            }
            return result;
        }
    }

    public List<BrowserInfo> getAllBrowsers() {
        synchronized (lock) {return new ArrayList<>(browserList.values());}
    }

    public String getImageByBrowserNameAndVersion(String browserName, String version) {
        synchronized (lock) {
            BrowserInfo browserInfo = browserList.get(getBrowserKey(browserName, version));
            if (browserInfo == null) {
                return defaultBrowsers.get(browserName).getDockerImageName();
            } else {
                return browserInfo.getDockerImageName();
            }
        }
    }

    protected String getBrowserKey(String browserName, String version) {
        return browserName + ":" + version;
    }

    protected List<BrowserInfo> readBrowsersFromFile() throws Exception {

        File file = resourceLoader
                .getResource("file:" + browserProperties.getConfigDir().replace("\\", "/"))
                .getFile();

        Map<String, Map<String, Object>> data = mapper.readValue(
                file, new TypeReference<>() {
                }
        );
        List<BrowserInfo> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> browserEntry : data.entrySet()) {
            String name = browserEntry.getKey();
            Map<String, Object> browserDetails = browserEntry.getValue();
            String defaultVersion = (String) browserDetails.get("default");
            Map<String, Map<String, String>> versions = (Map<String, Map<String, String>>) browserDetails.get("versions");
            for (Map.Entry<String, Map<String, String>> versionEntry : versions.entrySet()) {
                String version = versionEntry.getKey();
                String dockerImage = versionEntry.getValue().get("image");
                boolean isDefault = version.equals(defaultVersion);
                result.add(new BrowserInfo(name, version, dockerImage, isDefault));
            }
        }
        return result;
    }

    protected void writeBrowsersToFile(List<BrowserInfo> browsers) throws Exception {

        File file = resourceLoader
                .getResource("file:" + browserProperties.getConfigDir().replace("\\", "/"))
                .getFile();

        Map<String, Object> data = new LinkedHashMap<>();
        for (BrowserInfo browser : browsers) {
            data.computeIfAbsent(browser.getName(), k -> {
                Map<String, Object> map = new HashMap<>();
                map.put("default", null);
                map.put("versions", new LinkedHashMap<String, Object>());
                return map;
            });
            Map<String, Object> browserMap = (Map<String, Object>) data.get(browser.getName());
            Map<String, Object> versions = (Map<String, Object>) browserMap.get("versions");
            versions.put(browser.getVersion(), Map.of("image", browser.getDockerImageName()));
            if (browser.getIsDefault()) {
                browserMap.put("default", browser.getVersion());
            }
        }
        for (Object browserObj : data.values()) {
            Map<String, Object> browserMap = (Map<String, Object>) browserObj;
            if (browserMap.get("default") == null) {
                Map<String, Object> versions = (Map<String, Object>) browserMap.get("versions");
                if (!versions.isEmpty()) {
                    browserMap.put("default", versions.keySet().iterator().next());
                }
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
    }
}
