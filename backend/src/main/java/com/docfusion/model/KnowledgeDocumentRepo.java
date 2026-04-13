package com.docfusion.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface KnowledgeDocumentRepo extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByFileType(String fileType);
    List<KnowledgeDocument> findByCategory(String category);
    List<KnowledgeDocument> findByFileNameContainingIgnoreCase(String keyword);
    List<KnowledgeDocument> findByProcessed(Boolean processed);
    List<KnowledgeDocument> findByLibraryType(String libraryType);
    List<KnowledgeDocument> findByLibraryTypeAndSubDatabase(String libraryType, String subDatabase);
    @Query("SELECT DISTINCT d.subDatabase FROM KnowledgeDocument d WHERE d.libraryType = 'database' AND d.subDatabase IS NOT NULL")
    List<String> findAllSubDatabases();
}
