package com.kart.track.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块六-b：红旗车辆核查清单。
 *
 * <p>发起红旗时，以当时仍在赛的车辆一次性生成，一台车一行（{@code uk_check_event_kart}
 * 保证不重复）。结论：CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修。
 *
 * <p>各行互相独立：一台车退出或送修，不释放、不改写其它车辆的核查结论。
 * 选 REPAIR 时车辆进入 MAINTENANCE 并生成可追踪的待修维修单（{@code maintenanceId}）。
 */
@Data
@Entity
@Table(name = "red_flag_check")
public class RedFlagCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    /** 当时驾驶该车的车手（快照），可能为空（已派车但尚无圈速） */
    @Column(name = "driver_id")
    private Long driverId;

    /** PENDING 待核查 / CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "checked_by", length = 30)
    private String checkedBy;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** 需要维修时生成的待修维修单 id（可追踪） */
    @Column(name = "maintenance_id")
    private Long maintenanceId;

    /** 乐观锁版本：过期页面拿着旧版本提交会被拒 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
