package com.agentic.gateway.orchestrator.persistence;

import com.agentic.gateway.orchestrator.TaskState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * DevTask 生命週期持久化儲存庫。
 */
public interface DevTaskRepository extends JpaRepository<DevTaskRecord, String> {

    List<DevTaskRecord> findByCurrentStateIn(Collection<TaskState> states);

    List<DevTaskRecord> findByCurrentStateInOrderByUpdatedAtDesc(List<TaskState> states);

    Optional<DevTaskRecord> findByDeliveryId(String deliveryId);
}
