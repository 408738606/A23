package com.docfusion.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String sessionId;
    @Column(nullable = false)
    private String role;
    @Column(columnDefinition = "CLOB", nullable = false)
    private String content;
    @Column(columnDefinition = "CLOB")
    private String outputData;
    private String outputType;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getOutputData() { return outputData; }
    public void setOutputData(String v) { this.outputData = v; }
    public String getOutputType() { return outputType; }
    public void setOutputType(String v) { this.outputType = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
