package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.RedFlagEvent;

public interface RedFlagEventRepository extends JpaRepository<RedFlagEvent, Long> {

    /** 当前生效中的红旗（active_session_id 唯一索引，同场至多一个） */
    Optional<RedFlagEvent> findByActiveSessionId(Long sessionId);

    boolean existsByActiveSessionId(Long sessionId);

    /** 处置台主屏：按发生时刻倒序 */
    List<RedFlagEvent> findAllByOrderByOccurredAtDesc();

    /** 场次详情回看历史红旗事件 */
    List<RedFlagEvent> findBySessionIdOrderByOccurredAtDesc(Long sessionId);
}
