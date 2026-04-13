package com.docfusion.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String fileName;
    @Column(nullable = false)
    private String fileType;
    @Column(nullable = false)
    private String filePath;
    @Column(columnDefinition = "CLOB")
    private String extractedText;
    @Column(columnDefinition = "CLOB")
    private String summary;
    private Long fileSize;
    private String category;
    // "database" | "learning"
    private String libraryType = "database";
    // sub-database name (only for libraryType=database)
    private String subDatabase;
    private Boolean processed = false;
    private LocalDateTime uploadTime;
    private LocalDateTime lastModified;

    @PrePersist
    public void prePersist() { uploadTime = LocalDateTime.now(); lastModified = LocalDateTime.now(); }
    @PreUpdate
    public void preUpdate() { lastModified = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getFileType() { return fileType; }
    public void setFileType(String v) { this.fileType = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { this.filePath = v; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String v) { this.extractedText = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long v) { this.fileSize = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getLibraryType() { return libraryType; }
    public void setLibraryType(String v) { this.libraryType = v; }
    public String getSubDatabase() { return subDatabase; }
    public void setSubDatabase(String v) { this.subDatabase = v; }
    public Boolean getProcessed() { return processed; }
    public void setProcessed(Boolean v) { this.processed = v; }
    public LocalDateTime getUploadTime() { return uploadTime; }
    public void setUploadTime(LocalDateTime v) { this.uploadTime = v; }
    public LocalDateTime getLastModified() { return lastModified; }
    public void setLastModified(LocalDateTime v) { this.lastModified = v; }
}
