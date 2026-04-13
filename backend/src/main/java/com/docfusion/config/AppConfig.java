package com.docfusion.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import java.io.File;

@Configuration
public class AppConfig {

    @Value("${app.knowledge-base.path}")
    private String knowledgeBasePath;

    @Value("${app.upload.temp-path}")
    private String uploadTempPath;

    @Value("${app.output.path}")
    private String outputPath;

    @PostConstruct
    public void init() {
        new File(knowledgeBasePath).mkdirs();
        new File(uploadTempPath).mkdirs();
        new File(outputPath).mkdirs();
        new File("./data").mkdirs();
        new File("./logs").mkdirs();
    }

    public String getKnowledgeBasePath() { return knowledgeBasePath; }
    public String getUploadTempPath() { return uploadTempPath; }
    public String getOutputPath() { return outputPath; }
}
