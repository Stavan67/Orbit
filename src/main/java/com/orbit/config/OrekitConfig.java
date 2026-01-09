package com.orbit.config;

import lombok.extern.slf4j.Slf4j;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Configuration
@Slf4j
public class OrekitConfig {

    @PostConstruct
    public void initOrekit() {
        try {
            log.info("Initializing Orekit...");
            Path orekitDataDir = Files.createTempDirectory("orekit-data");
            try (InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("orekit-data.zip")) {
                if (is != null) {
                    extractZip(is, orekitDataDir);
                    log.info("Extracted Orekit data to: {}", orekitDataDir);
                } else {
                    log.warn("orekit-data.zip not found in classpath, trying alternative initialization");
                }
            }
            DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
            manager.addProvider(new DirectoryCrawler(orekitDataDir.toFile()));
            log.info("Orekit initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Orekit", e);
            throw new RuntimeException("Orekit initialization failed", e);
        }
    }

    private void extractZip(InputStream zipInputStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path filePath = targetDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    Files.copy(zis, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}