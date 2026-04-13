package com.docfusion.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LlmConfigRepo extends JpaRepository<LlmConfig, Long> {
    Optional<LlmConfig> findByIsDefaultTrue();
    List<LlmConfig> findByIsActiveTrue();
    Optional<LlmConfig> findByConfigName(String configName);
}
