package com.kart.track.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagCheck;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.RedFlagDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.MaintenanceRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagCheckRepository;
import com.kart.track.repository.RedFlagEventRepository;

/**
 * 模块六：红旗事件处置台。
 *
 * <p>一条红旗的完整生命周期都在本服务收口，且每个写动作都在单个数据库事务里，
 * 配合场次行级锁（{@code findByIdForUpdate}）、{@code active_session_id} 唯一索引、
 * 实体 {@code @Version} 乐观锁，解决需求里点名的四件事：
 * <ol>
 *   <li><b>并发幂等</b>：同场两名值班员几乎同时发起红旗，行锁串行化 + 唯一索引兜底，
 *       只产生一个事件、一套核查清单，重复请求返回同一事件；</li>
 *   <li><b>计时隔离</b>：红旗期间圈速落 PENDING，挂在本次事件下，不入榜、不刷个人最佳，
 *       主管逐条 CONFIRMED / VOIDED 后才解套，恢复后刷新不会重复入榜；</li>
 *   <li><b>核查独立</b>：一台车一结论，某台退出/送修不释放也不改写其它车；REPAIR 立即
 *       生成可追踪待修单、车辆进 MAINTENANCE；</li>
 *   <li><b>失败一致</b>：强制终止一次性结束场次 + 释放车手/可用车 + 保留退出/送修车，
 *       任一步抛错整体回滚，不留下半完成状态。</li>
 * </ol>
 */
@Service
public class RedFlagService {

    private final RedFlagEventRepository eventRepository;
    private final RedFlagCheckRepository checkRepository;
    private final RaceSessionRepository sessionRepository;
    private final LapRecordRepository lapRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final MaintenanceRepository maintenanceRepository;

    public RedFlagService(RedFlagEventRepository eventRepository,
                          RedFlagCheckRepository checkRepository,
                          RaceSessionRepository sessionRepository,
                          LapRecordRepository lapRepository,
                          DriverRepository driverRepository,
                          KartRepository kartRepository,
                          MaintenanceRepository maintenanceRepository) {
        this.eventRepository = eventRepository;
        this.checkRepository = checkRepository;
        this.sessionRepository = sessionRepository;
        this.lapRepository = lapRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.maintenanceRepository = maintenanceRepository;
    }

    // ============================ 值班员：发起红旗 ============================

    /**
     * 发起红旗：场次 → 立即进入「红旗处理中」，并以当时仍在赛车辆生成待核查清单。
     * 重复（并发）请求返回同一事件，不生成第二套清单。
     */
    @Transactional
    public Views.RedFlagEventView raise(Long sessionId, RedFlagDtos.RaiseReq req, String operator) {
        // 先锁场次行：把同一场次上的并发发起/恢复/终止彻底串行化
        RaceSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"RUNNING".equals(session.getStatus())) {
            throw new BizException("只有「进行中」的场次可以发起红旗，当前为"
                    + TrackDict.sessionStatusName(session.getStatus()));
        }

        // 幂等：本场已有生效红旗（锁内复查，挡住两名值班员的接近操作）
        RedFlagEvent existing = eventRepository.findByActiveSessionId(sessionId).orElse(null);
        if (existing != null) {
            return toView(existing);
        }

        LocalDateTime occurredAt = parseTime(req.getOccurredAt());
        long seq = eventRepository.findBySessionIdOrderByOccurredAtDesc(sessionId).size() + 1L;

        RedFlagEvent event = new RedFlagEvent();
        event.setEventNo("RF-" + session.getSessionNo() + "-" + seq);
        event.setSessionId(sessionId);
        event.setReason(req.getReason().trim());
        event.setRaisedBy(emptyTo(operator, "值班员"));
        event.setOccurredAt(occurredAt);
        event.setStatus("ACTIVE");
        event.setActiveSessionId(sessionId);
        event.setCreatedAt(LocalDateTime.now());
        event = eventRepository.save(event);

        // 以「当时仍在赛」的车辆（kart.status=ON_TRACK）一次性生成待核查清单——
        // 是否“仍在赛”以车辆状态为准，无本场圈速的已派车也要核查（车手快照可能为空=待发车）
        List<LapRecord> sessionLaps = lapRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        Map<Long, Long> kartDriver = new HashMap<>();
        for (LapRecord lap : sessionLaps) {
            kartDriver.putIfAbsent(lap.getKartId(), lap.getDriverId());
        }
        LocalDateTime now = LocalDateTime.now();
        for (Kart kart : kartRepository.findAllByStatusOrderByKartNoAsc("ON_TRACK")) {
            RedFlagCheck check = new RedFlagCheck();
            check.setEventId(event.getId());
            check.setSessionId(sessionId);
            check.setKartId(kart.getId());
            check.setDriverId(kartDriver.get(kart.getId()));
            check.setResult("PENDING");
            check.setCreatedAt(now);
            checkRepository.save(check);
        }
        return toView(event);
    }

    // ============================ 值班员：车辆核查 ============================

    /**
     * 填写一台车的核查结论：CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修。
     * 结论互相独立且一旦给出不可被别人覆盖；REPAIR 立即生成待修单并把车置为维修。
     */
    @Transactional
    public Views.RedFlagEventView submitCheck(Long eventId, Long checkId, RedFlagDtos.CheckReq req, String operator) {
        RedFlagEvent event = lockEvent(eventId);
        RedFlagCheck check = checkRepository.findById(checkId)
                .orElseThrow(() -> new BizException("核查记录不存在"));
        if (!check.getEventId().equals(eventId)) {
            throw new BizException("该核查记录不属于本次红旗事件");
        }

        String result = req.getResult();
        if (!"CONTINUE".equals(result) && !"RETIRE".equals(result) && !"REPAIR".equals(result)) {
            throw new BizException("核查结论只能是 可继续/退出比赛/需要维修");
        }

        // 过期页面：拿着旧版本号提交直接拒绝，不覆盖新结论
        if (req.getVersion() != null && !req.getVersion().equals(check.getVersion())) {
            throw new BizException("该车辆核查已被更新，请刷新后再操作（页面版本已过期）");
        }

        if (!"PENDING".equals(check.getResult())) {
            // 结论独立且不可改写：相同结论幂等返回，不同结论明确拒绝
            if (check.getResult().equals(result)) {
                return toView(event);
            }
            throw new BizException("该车已给出「" + TrackDict.checkResultName(check.getResult())
                    + "」结论，不能改判为「" + TrackDict.checkResultName(result) + "」");
        }

        String note = req.getNote() == null ? null : req.getNote().trim();
        Kart kart = kartRepository.findById(check.getKartId())
                .orElseThrow(() -> new BizException("车辆不存在"));

        if ("REPAIR".equals(result)) {
            if (note == null || note.isBlank()) {
                throw new BizException("选择「需要维修」时必须填写故障描述，用于生成待修记录");
            }
            // 车辆进入维修状态 + 形成可追踪待修单（同一事务，缺一不可）
            Maintenance order = new Maintenance();
            order.setOrderNo("MT-RF" + event.getId() + "-K" + kart.getKartNo().replace("K-", ""));
            order.setKartId(kart.getId());
            order.setMaintType("BODY");
            order.setFaultDesc("红旗事件 " + event.getEventNo() + " 核查送修：" + note);
            order.setWorkHours(BigDecimal.ZERO);
            order.setPartsFee(BigDecimal.ZERO);
            order.setTotalFee(BigDecimal.ZERO);
            order.setStatus("WAITING");
            order.setStartTime(LocalDateTime.now());
            order = maintenanceRepository.save(order);
            check.setMaintenanceId(order.getId());

            kart.setStatus("MAINTENANCE");
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
            releaseDriver(check.getDriverId(), event.getSessionId());
        } else if ("RETIRE".equals(result)) {
            // 退出：立即释放该车占用的车手；车辆保留在赛状态之外由收车/终止收口，不改其它车
            releaseDriver(check.getDriverId(), event.getSessionId());
        }
        // CONTINUE：车、人均不动，恢复后继续跑

        check.setResult(result);
        check.setNote(note);
        check.setCheckedBy(emptyTo(operator, "值班员"));
        check.setCheckedAt(LocalDateTime.now());
        checkRepository.save(check);
        return toView(event);
    }

    // ============================ 主管：裁定红旗圈速 ============================

    /**
     * 裁定一条红旗期间上报的圈速：CONFIRMED 确认有效（入榜、计最快/个人最佳/里程）
     * 或 VOIDED 作废（valid=VOID，永不入榜）。只有主管可操作。
     */
    @Transactional
    public Views.RedFlagEventView adjudicate(Long eventId, Long lapId,
                                             RedFlagDtos.AdjudicateReq req, String operator) {
        RedFlagEvent event = lockEvent(eventId);
        LapRecord lap = lapRepository.findById(lapId)
                .orElseThrow(() -> new BizException("圈速记录不存在"));
        if (lap.getRedFlagEventId() == null || !lap.getRedFlagEventId().equals(eventId)) {
            throw new BizException("该圈速不属于本次红旗事件的待裁定记录");
        }
        if (!"PENDING".equals(lap.getAdjudication())) {
            // 已裁定：相同结论幂等，不同结论不允许翻案（恢复后刷新也不会重复入榜）
            boolean same = ("CONFIRMED".equals(req.getAdjudication()) && "CONFIRMED".equals(lap.getAdjudication()))
                    || ("VOIDED".equals(req.getAdjudication()) && "VOIDED".equals(lap.getAdjudication()));
            if (same) {
                return toView(event);
            }
            throw new BizException("该圈速已" + TrackDict.adjudicationName(lap.getAdjudication()) + "，不能重复改判");
        }

        String decision = req.getAdjudication();
        if (!"CONFIRMED".equals(decision) && !"VOIDED".equals(decision)) {
            throw new BizException("裁定结果只能是 确认有效 / 作废");
        }

        if ("CONFIRMED".equals(decision)) {
            // 解套：此时才真正计入最快圈、个人历史最好成绩、车辆里程
            lap.setAdjudication("CONFIRMED");
            lapRepository.save(lap);
            applyConfirmedLap(lap);
        } else {
            lap.setAdjudication("VOIDED");
            lap.setValid("VOID");
            lap.setFastest(false);
            lapRepository.save(lap);
            recalculateFastest(lap.getSessionId());
            recomputeDriverBest(lap.getDriverId());
        }
        return toView(event);
    }

    // ============================ 主管：恢复比赛 ============================

    /**
     * 恢复比赛：必须同时满足——全部车辆已核查、红旗圈速全部裁定、场次仍由本次事件控制。
     * 恢复后正常记圈；旧待裁定记录已逐条定性，刷新/重复恢复都不会再次入榜。
     */
    @Transactional
    public Views.RedFlagEventView resume(Long eventId, RedFlagDtos.ResolveReq req, String operator) {
        RedFlagEvent event = lockEvent(eventId);
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));

        if (!"ACTIVE".equals(event.getStatus()) || !event.getSessionId().equals(session.getId())
                || event.getActiveSessionId() == null) {
            throw new BizException("场次已不在本次红旗事件控制下，不能恢复（事件状态："
                    + TrackDict.redFlagStatusName(event.getStatus()) + "）");
        }
        if (!"RUNNING".equals(session.getStatus())) {
            throw new BizException("场次当前为" + TrackDict.sessionStatusName(session.getStatus()) + "，不能恢复");
        }

        long total = checkRepository.countByEventId(eventId);
        long pending = checkRepository.countByEventIdAndResult(eventId, "PENDING");
        if (pending > 0) {
            throw new BizException("还有 " + pending + "/" + total + " 台车未核查，全部核查后才能恢复比赛");
        }
        long lapPending = lapRepository.countByRedFlagEventIdAndAdjudication(eventId, "PENDING");
        if (lapPending > 0) {
            throw new BizException("还有 " + lapPending + " 条红旗期间圈速未裁定，全部裁定后才能恢复比赛");
        }

        closeEvent(event, "RESUME", req, operator);
        // 场次保持 RUNNING，CONTINUE 的车仍 ON_TRACK，可继续记圈
        return toView(event);
    }

    // ============================ 主管：强制终止（收口） ============================

    /**
     * 强制终止：一个事务里结束场次、释放车手与可用车辆、保留退出/送修车辆状态、
     * 把残留待裁定圈速统一作废、落处置记录。任一步失败整体回滚。
     */
    @Transactional
    public Views.RedFlagEventView terminate(Long eventId, RedFlagDtos.ResolveReq req, String operator) {
        RedFlagEvent event = lockEvent(eventId);
        RaceSession session = sessionRepository.findByIdForUpdate(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("红旗事件已处置（" + TrackDict.redFlagStatusName(event.getStatus())
                    + "），请勿重复操作");
        }
        if ("FINISHED".equals(session.getStatus()) || "CANCELED".equals(session.getStatus())) {
            throw new BizException("场次已结束，不能重复终止");
        }

        // 1) 残留待裁定圈速一次性作废，杜绝悬空记录
        for (LapRecord lap : lapRepository.findByRedFlagEventIdAndAdjudicationOrderByCreatedAtDesc(eventId, "PENDING")) {
            lap.setAdjudication("VOIDED");
            lap.setValid("VOID");
            lap.setFastest(false);
            lapRepository.save(lap);
        }

        // 2) 结束场次
        session.setStatus("FINISHED");
        session.setEndTime(LocalDateTime.now());
        sessionRepository.save(session);

        // 3) 释放本场全部车手（含继续/退出/送修/未核查），累计场次 +1
        for (Driver driver : driverRepository.findAllBySessionIdOrderByBestLapMsAsc(session.getId())) {
            driver.setTotalSessions(driver.getTotalSessions() + 1);
            driver.setSessionId(null);
            driverRepository.save(driver);
        }

        // 4) 车辆按核查结论分流。一场只可能有一个生效红旗，此刻所有 ON_TRACK 车都属本场：
        //    退出（RETIRE）→ 保留在赛状态；送修（REPAIR）→ 已 MAINTENANCE 天然保留；
        //    其余（可继续/未核查/已派车无圈速）→ 收车回 AVAILABLE
        java.util.Set<Long> retireKarts = new java.util.HashSet<>();
        for (RedFlagCheck check : checkRepository.findByEventIdOrderByKartIdAsc(eventId)) {
            if ("RETIRE".equals(check.getResult())) {
                retireKarts.add(check.getKartId());
            }
        }
        for (Kart kart : kartRepository.findAllByStatusOrderByKartNoAsc("ON_TRACK")) {
            if (retireKarts.contains(kart.getId())) {
                continue; // 退出车辆保留在赛状态，不随场次结束悄悄放回可用
            }
            kart.setStatus("AVAILABLE");
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }

        // 5) 落处置记录
        closeEvent(event, "TERMINATE", req, operator);

        // 6) 提交前一致性自检：送修必须有维修单且车在维修；任何不满足 → 抛错整体回滚
        for (RedFlagCheck check : checkRepository.findByEventIdOrderByKartIdAsc(eventId)) {
            if ("REPAIR".equals(check.getResult())) {
                if (check.getMaintenanceId() == null
                        || maintenanceRepository.findById(check.getMaintenanceId()).isEmpty()) {
                    throw new BizException("送修车辆缺少维修记录，终止已回滚以避免半完成状态");
                }
                Kart kart = kartRepository.findById(check.getKartId()).orElse(null);
                if (kart == null || !"MAINTENANCE".equals(kart.getStatus())) {
                    throw new BizException("送修车辆未处于维修状态，终止已回滚以避免半完成状态");
                }
            }
        }
        return toView(event);
    }

    // ============================ 查询：处置台 / 历史 ============================

    @Transactional(readOnly = true)
    public Views.RedFlagEventView get(Long id) {
        return toView(eventRepository.findById(id)
                .orElseThrow(() -> new BizException("红旗事件不存在")));
    }

    /** 主屏当前生效红旗；没有则 null */
    @Transactional(readOnly = true)
    public Views.RedFlagEventView active(Long sessionId) {
        return eventRepository.findByActiveSessionId(sessionId).map(this::toView).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Views.RedFlagHistoryView> history(Long sessionId) {
        List<Views.RedFlagHistoryView> list = new ArrayList<>();
        for (RedFlagEvent event : eventRepository.findBySessionIdOrderByOccurredAtDesc(sessionId)) {
            list.add(toHistoryView(event));
        }
        return list;
    }

    // ============================ 内部辅助 ============================

    /** 锁场次行后取事件，保证事件处置与场次状态、圈速/核查写入互斥 */
    private RedFlagEvent lockEvent(Long eventId) {
        RedFlagEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BizException("红旗事件不存在"));
        sessionRepository.findByIdForUpdate(event.getSessionId());
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("红旗事件" + TrackDict.redFlagStatusName(event.getStatus())
                    + "，请刷新查看最终处置结果");
        }
        return event;
    }

    private void closeEvent(RedFlagEvent event, String resolution,
                            RedFlagDtos.ResolveReq req, String operator) {
        event.setStatus("RESUME".equals(resolution) ? "RESUMED" : "TERMINATED");
        event.setResolution(resolution);
        event.setResolvedBy(emptyTo(operator, "主管"));
        event.setResolvedAt(LocalDateTime.now());
        event.setResolutionNote(req == null || req.getNote() == null ? null : req.getNote().trim());
        event.setActiveSessionId(null);
        eventRepository.save(event);
    }

    /** 释放车手（仅当其仍挂在本场） */
    private void releaseDriver(Long driverId, Long sessionId) {
        if (driverId == null) {
            return;
        }
        driverRepository.findById(driverId).ifPresent(driver -> {
            if (sessionId.equals(driver.getSessionId())) {
                driver.setSessionId(null);
                driverRepository.save(driver);
            }
        });
    }

    /** 确认有效：刷本场最快圈、个人历史最佳、车辆里程/工时（与正常圈同一套口径） */
    private void applyConfirmedLap(LapRecord lap) {
        recalculateFastest(lap.getSessionId());
        Driver driver = driverRepository.findById(lap.getDriverId()).orElse(null);
        if (driver != null && (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0
                || lap.getLapTimeMs() < driver.getBestLapMs())) {
            driver.setBestLapMs(lap.getLapTimeMs());
            driverRepository.save(driver);
        }
        RaceSession session = sessionRepository.findById(lap.getSessionId()).orElse(null);
        Kart kart = kartRepository.findById(lap.getKartId()).orElse(null);
        if (session != null && kart != null) {
            BigDecimal km = TrackDict.configLapKm(session.getTrackConfig());
            kart.setTotalKm(kart.getTotalKm().add(km));
            kart.setTotalHours(kart.getTotalHours().add(BigDecimal.valueOf(lap.getLapTimeMs() / 3600000.0)));
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }
    }

    /** 重算本场最快圈：清掉旧标记，给当前可入榜的最快 VALID 圈打标 */
    private void recalculateFastest(Long sessionId) {
        for (LapRecord lap : lapRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)) {
            if (lap.isFastest()) {
                lap.setFastest(false);
                lapRepository.save(lap);
            }
        }
        List<LapRecord> ranked = lapRepository.findRankedValid(sessionId);
        if (!ranked.isEmpty()) {
            LapRecord best = ranked.get(0);
            best.setFastest(true);
            lapRepository.save(best);
        }
    }

    /** 重算车手历史最佳：只统计可入榜的有效圈（红旗待裁定/作废均排除） */
    private void recomputeDriverBest(Long driverId) {
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
            return;
        }
        Integer best = null;
        for (LapRecord lap : lapRepository.findByDriverIdOrderByLapTimeMsAsc(driverId)) {
            if ("VALID".equals(lap.getValid()) && !"PENDING".equals(lap.getAdjudication())) {
                best = lap.getLapTimeMs();
                break;
            }
        }
        driver.setBestLapMs(best == null ? 0 : best);
        driverRepository.save(driver);
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            throw new BizException("发生时刻格式应为 yyyy-MM-ddTHH:mm:ss");
        }
    }

    private String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long durationOf(RedFlagEvent event) {
        LocalDateTime end = event.getResolvedAt() != null ? event.getResolvedAt()
                : ("ACTIVE".equals(event.getStatus()) ? LocalDateTime.now() : event.getOccurredAt());
        long seconds = Duration.between(event.getOccurredAt(), end).getSeconds();
        return Math.max(0, seconds);
    }

    // ---------------- 视图拼装 ----------------

    private Views.RedFlagEventView toView(RedFlagEvent event) {
        List<RedFlagCheck> checks = checkRepository.findByEventIdOrderByKartIdAsc(event.getId());
        List<LapRecord> laps = lapRepository.findByRedFlagEventIdOrderByCreatedAtDesc(event.getId());

        Map<Long, String> driverName = new HashMap<>();
        for (Driver d : driverRepository.findAll()) {
            driverName.put(d.getId(), d.getName());
        }
        Map<Long, Kart> karts = new HashMap<>();
        for (Kart k : kartRepository.findAll()) {
            karts.put(k.getId(), k);
        }
        Map<Long, String> maintOrder = new HashMap<>();
        for (Maintenance m : maintenanceRepository.findAll()) {
            maintOrder.put(m.getId(), m.getOrderNo());
        }
        Map<Long, String> sessionNo = new HashMap<>();
        for (RaceSession s : sessionRepository.findAll()) {
            sessionNo.put(s.getId(), s.getSessionNo());
        }

        long total = checks.size();
        long pending = checks.stream().filter(c -> "PENDING".equals(c.getResult())).count();
        long cont = checks.stream().filter(c -> "CONTINUE".equals(c.getResult())).count();
        long retire = checks.stream().filter(c -> "RETIRE".equals(c.getResult())).count();
        long repair = checks.stream().filter(c -> "REPAIR".equals(c.getResult())).count();

        List<Views.RedFlagCheckView> checkViews = new ArrayList<>();
        for (RedFlagCheck c : checks) {
            Kart kart = karts.get(c.getKartId());
            checkViews.add(new Views.RedFlagCheckView(
                    c.getId(),
                    c.getEventId(),
                    c.getKartId(),
                    kart == null ? "—" : kart.getKartNo(),
                    kart == null ? "—" : TrackDict.kartModelName(kart.getModel()),
                    c.getDriverId(),
                    c.getDriverId() == null ? "待发车" : driverName.getOrDefault(c.getDriverId(), "—"),
                    c.getResult(),
                    TrackDict.checkResultName(c.getResult()),
                    c.getNote(),
                    c.getCheckedBy(),
                    c.getCheckedAt(),
                    c.getMaintenanceId(),
                    c.getMaintenanceId() == null ? null : maintOrder.get(c.getMaintenanceId()),
                    c.getVersion()));
        }

        List<Views.LapRecordView> lapViews = new ArrayList<>();
        for (LapRecord lap : laps) {
            Kart kart = karts.get(lap.getKartId());
            lapViews.add(new Views.LapRecordView(
                    lap.getId(),
                    lap.getSessionId(),
                    sessionNo.getOrDefault(lap.getSessionId(), "—"),
                    lap.getDriverId(),
                    driverName.getOrDefault(lap.getDriverId(), "—"),
                    lap.getKartId(),
                    kart == null ? "—" : kart.getKartNo(),
                    lap.getLapIndex(),
                    lap.getLapTimeMs(),
                    TrackDict.formatLap(lap.getLapTimeMs()),
                    lap.isFastest(),
                    lap.getValid(),
                    TrackDict.lapValidName(lap.getValid()),
                    lap.getCreatedAt(),
                    lap.getRedFlagEventId(),
                    lap.getAdjudication(),
                    TrackDict.adjudicationName(lap.getAdjudication()),
                    !"PENDING".equals(lap.getAdjudication()) && "VALID".equals(lap.getValid())));
        }

        long lapPending = laps.stream().filter(l -> "PENDING".equals(l.getAdjudication())).count();
        long lapConfirmed = laps.stream().filter(l -> "CONFIRMED".equals(l.getAdjudication())).count();
        long lapVoided = laps.stream().filter(l -> "VOIDED".equals(l.getAdjudication())).count();
        boolean allChecked = pending == 0;
        boolean allAdjudicated = lapPending == 0;
        boolean active = "ACTIVE".equals(event.getStatus());

        return new Views.RedFlagEventView(
                event.getId(),
                event.getEventNo(),
                event.getSessionId(),
                sessionNo.getOrDefault(event.getSessionId(), "—"),
                event.getReason(),
                event.getRaisedBy(),
                event.getOccurredAt(),
                event.getStatus(),
                TrackDict.redFlagStatusName(event.getStatus()),
                event.getResolution(),
                TrackDict.redFlagResolutionName(event.getResolution()),
                event.getResolvedBy(),
                event.getResolvedAt(),
                event.getResolutionNote(),
                durationOf(event),
                event.getVersion(),
                total,
                pending,
                cont,
                retire,
                repair,
                allChecked,
                lapPending,
                lapConfirmed,
                lapVoided,
                allAdjudicated,
                active && allChecked && allAdjudicated,
                active,
                checkViews,
                lapViews);
    }

    private Views.RedFlagHistoryView toHistoryView(RedFlagEvent event) {
        List<RedFlagCheck> checks = checkRepository.findByEventIdOrderByKartIdAsc(event.getId());
        List<LapRecord> laps = lapRepository.findByRedFlagEventIdOrderByCreatedAtDesc(event.getId());
        return new Views.RedFlagHistoryView(
                event.getId(),
                event.getEventNo(),
                event.getReason(),
                event.getRaisedBy(),
                event.getOccurredAt(),
                event.getStatus(),
                TrackDict.redFlagStatusName(event.getStatus()),
                TrackDict.redFlagResolutionName(event.getResolution()),
                event.getResolvedBy(),
                event.getResolvedAt(),
                durationOf(event),
                checks.size(),
                checks.stream().filter(c -> "CONTINUE".equals(c.getResult())).count(),
                checks.stream().filter(c -> "RETIRE".equals(c.getResult())).count(),
                checks.stream().filter(c -> "REPAIR".equals(c.getResult())).count(),
                laps.stream().filter(l -> "CONFIRMED".equals(l.getAdjudication())).count(),
                laps.stream().filter(l -> "VOIDED".equals(l.getAdjudication())).count());
    }
}
