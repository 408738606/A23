package com.docfusion.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OutputFileRepo extends JpaRepository<OutputFile, Long> {
    List<OutputFile> findBySessionIdOrderByCreatedAtDesc(String sessionId);
    List<OutputFile> findBySavedToKnowledgeBase(Boolean saved);
}
