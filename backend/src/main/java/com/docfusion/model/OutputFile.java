package com.docfusion.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "output_files")
public class OutputFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionId;
    @Column(nullable = false)
    private String fileName;
    @Column(nullable = false)
    private String filePath;
    @Column(nullable = false)
    private String fileType;
    private Long fileSize;
    @Column(columnDefinition = "CLOB")
    private String description;
    private Boolean savedToKnowledgeBase = false;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { this.filePath = v; }
    public String getFileType() { return fileType; }
    public void setFileType(String v) { this.fileType = v; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long v) { this.fileSize = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Boolean getSavedToKnowledgeBase() { return savedToKnowledgeBase; }
    public void setSavedToKnowledgeBase(Boolean v) { this.savedToKnowledgeBase = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
