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
 * 模块六-a：红旗事件。
 *
 * <p>状态机：ACTIVE（红旗处理中）→ RESUMED（恢复比赛）/ TERMINATED（强制终止）。
 *
 * <p>并发收口：{@code activeSessionId} 仅在 ACTIVE 时等于场次 id，其余为 NULL，
 * 库表上有唯一索引 {@code uk_active_session}——同一场次两个值班员同时点「发起红旗」，
 * 只有一笔能写进 ACTIVE 行，另一笔撞唯一键后退回返回同一事件（见 RedFlagService）。
 *
 * <p>{@code version} 乐观锁：恢复/终止必须拿页面上看到的版本号提交，
 * 过期页面或重复点击会被拦，不能覆盖别人的处置。
 */
@Data
@Entity
@Table(name = "red_flag_event")
public class RedFlagEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 红旗事件号，如 RF-S2026092401-1 */
    @Column(name = "event_no", nullable = false, length = 28)
    private String eventNo;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 红旗原因（发起时必填） */
    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    /** 发起值班员 */
    @Column(name = "raised_by", nullable = false, length = 30)
    private String raisedBy;

    /** 发生时刻（发起时记录） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** ACTIVE 红旗处理中 / RESUMED 已恢复 / TERMINATED 已强制终止 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 仅 ACTIVE 时回填场次 id；唯一索引保证同场只有一个生效红旗 */
    @Column(name = "active_session_id")
    private Long activeSessionId;

    /** RESUME 恢复比赛 / TERMINATE 强制终止 */
    @Column(name = "resolution", length = 16)
    private String resolution;

    @Column(name = "resolved_by", length = 30)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 200)
    private String resolutionNote;

    /** 乐观锁版本：防过期页面 / 重复恢复或终止 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
