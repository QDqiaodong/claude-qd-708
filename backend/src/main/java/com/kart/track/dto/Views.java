package com.kart.track.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 */
public final class Views {

    private Views() {
    }

    /** 模块一：卡丁车 */
    public record KartView(
            Long id,
            String kartNo,
            String model,
            String modelName,
            Integer engineCc,
            BigDecimal maxPower,
            String status,
            String statusName,
            BigDecimal totalKm,
            BigDecimal totalHours,
            LocalDate lastServiceDate,
            Integer serviceIntervalH,
            boolean serviceDue,
            String serviceDueText,
            String sessionNo,
            LocalDateTime updatedAt) {
    }

    /** 模块二：车手 */
    public record DriverView(
            Long id,
            String memberNo,
            String name,
            String phone,
            String ageGroup,
            String ageGroupName,
            String licenseLevel,
            String licenseName,
            Integer totalSessions,
            Integer bestLapMs,
            String bestLapText,
            BigDecimal totalSpend,
            LocalDate registerDate,
            Long sessionId,
            String sessionNo) {
    }

    /** 模块三：场次 */
    public record SessionView(
            Long id,
            String sessionNo,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String trackConfig,
            String trackConfigName,
            String groupCode,
            Integer maxKarts,
            Integer signedCount,
            String status,
            String statusName,
            long elapsedSec,
            long lapCount,
            String fastestText) {
    }

    /** 模块四：圈速记录（写入走 @SQLInsert 手写 SQL） */
    public record LapRecordView(
            Long id,
            Long sessionId,
            String sessionNo,
            Long driverId,
            String driverName,
            Long kartId,
            String kartNo,
            Integer lapIndex,
            Integer lapTimeMs,
            String lapTimeText,
            boolean fastest,
            String valid,
            String validName,
            LocalDateTime createdAt) {
    }

    /** 模块五：维修保养 */
    public record MaintenanceView(
            Long id,
            String orderNo,
            Long kartId,
            String kartNo,
            String maintType,
            String maintTypeName,
            String faultDesc,
            BigDecimal workHours,
            BigDecimal partsFee,
            BigDecimal totalFee,
            String worker,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            String statusName,
            long durationMin) {
    }

    /** 实时圈速排行榜一行 */
    public record LeaderRow(
            int rank,
            String driverName,
            String kartNo,
            Integer bestLapMs,
            String lapText,
            String deltaText,
            int lapCount,
            boolean first) {
    }

    /** 赛道俯视图上的一个小车点位 */
    public record TrackDot(
            Long kartId,
            String kartNo,
            String status,
            String statusName,
            String driverName,
            double offset,
            double speed) {
    }

    /** 首页「赛道实况」一屏数据 */
    public record LiveView(
            SessionView session,
            List<LeaderRow> leaderboard,
            List<TrackDot> dots,
            long lapCount,
            String fastestText,
            String sessionSummary) {
    }

    /** 维修保养页的费用统计 */
    public record MaintSummary(
            long total,
            long waiting,
            long repairing,
            long done,
            BigDecimal partsFeeTotal,
            BigDecimal laborFeeTotal,
            BigDecimal totalFee) {
    }

    /** 顶栏计数器 */
    public record Overview(
            long kartTotal,
            long kartAvailable,
            long kartOnTrack,
            long kartMaintenance,
            long kartDisabled,
            long driverTotal,
            long sessionPending,
            long sessionRunning,
            long lapTotal,
            long maintOpen,
            long maintDone,
            BigDecimal maintFee) {
    }
}
