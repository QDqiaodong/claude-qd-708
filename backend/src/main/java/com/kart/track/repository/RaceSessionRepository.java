package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.kart.track.domain.RaceSession;

import jakarta.persistence.LockModeType;

public interface RaceSessionRepository extends JpaRepository<RaceSession, Long> {

    List<RaceSession> findAllByOrderByStartTimeDesc();

    Optional<RaceSession> findBySessionNo(String sessionNo);

    boolean existsBySessionNo(String sessionNo);

    List<RaceSession> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);

    /** 行级排他锁：发起红旗 / 恢复 / 终止这类收口动作先把场次行锁住，串行化并发请求 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from RaceSession s where s.id = :id")
    Optional<RaceSession> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
