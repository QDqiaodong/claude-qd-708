package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.RaceSession;

public interface RaceSessionRepository extends JpaRepository<RaceSession, Long> {

    List<RaceSession> findAllByOrderByStartTimeDesc();

    Optional<RaceSession> findBySessionNo(String sessionNo);

    boolean existsBySessionNo(String sessionNo);

    List<RaceSession> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);
}
