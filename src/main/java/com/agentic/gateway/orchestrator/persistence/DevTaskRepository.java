package com.agentic.gateway.orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * DevTask 生命週期持久化儲存庫。
 */
public interface DevTaskRepository extends JpaRepository<DevTaskRecord, String> {
}
