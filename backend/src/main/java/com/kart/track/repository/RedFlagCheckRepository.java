package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.RedFlagCheck;

public interface RedFlagCheckRepository extends JpaRepository<RedFlagCheck, Long> {

    /** 一次红旗的核查清单：按车号顺序由 service 拼装，这里先按 kartId 稳定排序 */
    List<RedFlagCheck> findByEventIdOrderByKartIdAsc(Long eventId);

    Optional<RedFlagCheck> findByEventIdAndKartId(Long eventId, Long kartId);

    long countByEventIdAndResult(Long eventId, String result);

    long countByEventId(Long eventId);
}
