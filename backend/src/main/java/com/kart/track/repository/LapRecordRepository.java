package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.LapRecord;

/**
 * 圈速记录。虽然实体上的写操作走 {@code @SQLInsert / @SQLUpdate / @SQLDelete} 手写 SQL，
 * 查询仍然用 Spring Data JPA 的派生方法（由 Hibernate 生成 select）。
 */
public interface LapRecordRepository extends JpaRepository<LapRecord, Long> {

    List<LapRecord> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    List<LapRecord> findBySessionIdOrderByLapTimeMsAsc(Long sessionId);

    List<LapRecord> findBySessionIdAndValidOrderByLapTimeMsAsc(Long sessionId, String valid);

    List<LapRecord> findByDriverIdOrderByLapTimeMsAsc(Long driverId);

    List<LapRecord> findByKartIdOrderByCreatedAtDesc(Long kartId);

    /** 某次红旗事件期间上报、待主管裁定的圈速 */
    List<LapRecord> findByRedFlagEventIdAndAdjudicationOrderByCreatedAtDesc(Long eventId, String adjudication);

    List<LapRecord> findByRedFlagEventIdOrderByCreatedAtDesc(Long eventId);

    long countBySessionId(Long sessionId);

    long countBySessionIdAndValid(Long sessionId, String valid);

    long countByCreatedAtAfter(java.time.LocalDateTime from);

    long countByRedFlagEventIdAndAdjudication(Long eventId, String adjudication);

    /**
     * 本场可计入排行榜/最快圈的记录：VALID 且不是红旗待裁定。
     * （CONFIRMED 的红旗圈也计入；PENDING 一律隔离。）
     */
    @Query("select l from LapRecord l where l.sessionId = :sessionId "
            + "and l.valid = 'VALID' and l.adjudication <> 'PENDING' "
            + "order by l.lapTimeMs asc")
    List<LapRecord> findRankedValid(@Param("sessionId") Long sessionId);
}
