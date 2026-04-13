package com.docfusion.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_configs")
public class LlmConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String configName;
    @Column(nullable = false)
    private String provider;
    @Column(nullable = false)
    private String baseUrl;
    private String apiKey;
    @Column(nullable = false)
    private String modelName;
    private Integer maxTokens = 4096;
    private Double temperature = 0.7;
    private Boolean isDefault = false;
    private Boolean isActive = true;
    // "api" | "local"
    private String modelCategory = "api";
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    public void preUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConfigName() { return configName; }
    public void setConfigName(String v) { this.configName = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer v) { this.maxTokens = v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { this.temperature = v; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean v) { this.isDefault = v; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean v) { this.isActive = v; }
    public String getModelCategory() { return modelCategory; }
    public void setModelCategory(String v) { this.modelCategory = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
