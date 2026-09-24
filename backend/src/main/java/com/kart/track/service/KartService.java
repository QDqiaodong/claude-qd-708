package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.KartDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.RaceSessionRepository;

/** 模块一：卡丁车（卡片墙 + 状态切换 + 派车到本场）。 */
@Service
public class KartService {

    private final KartRepository kartRepository;
    private final RaceSessionRepository sessionRepository;
    private final LapRecordRepository lapRecordRepository;

    public KartService(KartRepository kartRepository,
                       RaceSessionRepository sessionRepository,
                       LapRecordRepository lapRecordRepository) {
        this.kartRepository = kartRepository;
        this.sessionRepository = sessionRepository;
        this.lapRecordRepository = lapRecordRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.KartView> list() {
        // 在赛车辆的所属场次：从进行中场次的圈速记录里反查一次，避免逐车查询
        Map<Long, String> kartSession = new HashMap<>();
        for (RaceSession running : sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING")) {
            for (LapRecord lap : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(running.getId())) {
                kartSession.putIfAbsent(lap.getKartId(), running.getSessionNo());
            }
        }
        List<Views.KartView> list = new ArrayList<>();
        for (Kart kart : kartRepository.findAllByOrderByKartNoAsc()) {
            list.add(toView(kart, kartSession.get(kart.getId())));
        }
        return list;
    }

    @Transactional
    public Views.KartView create(KartDtos.KartSaveReq req) {
        if (!TrackDict.isValidKartModel(req.getModel())) {
            throw new BizException("车型只能是 娱乐车/竞技车/儿童车/双人车");
        }
        if (kartRepository.existsByKartNo(req.getKartNo())) {
            throw new BizException("车号 " + req.getKartNo() + " 已存在");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "AVAILABLE" : req.getStatus();
        if (!TrackDict.isValidKartStatus(status) || "ON_TRACK".equals(status)) {
            throw new BizException("新建车辆的初始状态只能是 可用/维修/停用");
        }
        Kart kart = new Kart();
        kart.setKartNo(req.getKartNo());
        kart.setModel(req.getModel());
        kart.setEngineCc(req.getEngineCc() == null ? 200 : req.getEngineCc());
        kart.setMaxPower(req.getMaxPower() == null ? BigDecimal.valueOf(9.0) : req.getMaxPower());
        kart.setStatus(status);
        kart.setTotalKm(req.getTotalKm() == null ? BigDecimal.ZERO : req.getTotalKm());
        kart.setTotalHours(req.getTotalHours() == null ? BigDecimal.ZERO : req.getTotalHours());
        kart.setLastServiceDate(req.getLastServiceDate());
        kart.setServiceIntervalH(req.getServiceIntervalH() == null ? 50 : req.getServiceIntervalH());
        kart.setUpdatedAt(LocalDateTime.now());
        return toView(kartRepository.save(kart), null);
    }

    /** 状态切换：可用 ↔ 维修 ↔ 停用（在赛只能由派车/收车产生） */
    @Transactional
    public Views.KartView changeStatus(Long id, KartDtos.StatusReq req) {
        Kart kart = kartRepository.findById(id).orElseThrow(() -> new BizException("车辆不存在"));
        String target = req.getStatus();
        if (!TrackDict.isValidKartStatus(target)) {
            throw new BizException("目标状态不合法");
        }
        if ("ON_TRACK".equals(target)) {
            throw new BizException("在赛状态只能通过「派车到本场」产生");
        }
        if ("ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 正在赛道上，请先收车再切换状态");
        }
        kart.setStatus(target);
        kart.setUpdatedAt(LocalDateTime.now());
        return toView(kartRepository.save(kart), null);
    }

    /** 派车到本场：可用车 → 在赛，并占用一个名额 */
    @Transactional
    public Views.KartView dispatch(Long id, KartDtos.DispatchReq req) {
        Kart kart = kartRepository.findById(id).orElseThrow(() -> new BizException("车辆不存在"));
        if (!"AVAILABLE".equals(kart.getStatus())) {
            throw new BizException("只有「可用」状态的车才能派上赛道，当前为" + TrackDict.kartStatusName(kart.getStatus()));
        }
        RaceSession session = resolveSession(req.getSessionId());
        if (!"PENDING".equals(session.getStatus()) && !"RUNNING".equals(session.getStatus())) {
            throw new BizException("场次 " + session.getSessionNo() + " 已结束或已取消，不能派车");
        }
        long used = lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId()).stream()
                .map(LapRecord::getKartId).distinct().count();
        if (used >= session.getMaxKarts()) {
            throw new BizException("本场车辆已达上限 " + session.getMaxKarts() + " 台");
        }
        kart.setStatus("ON_TRACK");
        kart.setUpdatedAt(LocalDateTime.now());
        kartRepository.save(kart);
        return toView(kart, session.getSessionNo());
    }

    /** 收车：在赛 → 可用 */
    @Transactional
    public Views.KartView recall(Long id) {
        Kart kart = kartRepository.findById(id).orElseThrow(() -> new BizException("车辆不存在"));
        if (!"ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 当前不在赛道上");
        }
        kart.setStatus("AVAILABLE");
        kart.setUpdatedAt(LocalDateTime.now());
        return toView(kartRepository.save(kart), null);
    }

    /** 未传 sessionId 时取「进行中」的场次，没有则取最近一场 */
    RaceSession resolveSession(Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId).orElseThrow(() -> new BizException("场次不存在"));
        }
        List<RaceSession> running = sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING");
        if (!running.isEmpty()) {
            return running.get(0);
        }
        List<RaceSession> all = sessionRepository.findAllByOrderByStartTimeDesc();
        if (all.isEmpty()) {
            throw new BizException("还没有任何场次，请先建场");
        }
        return all.get(0);
    }

    Views.KartView toView(Kart kart, String sessionNo) {
        boolean due = isServiceDue(kart);
        return new Views.KartView(
                kart.getId(),
                kart.getKartNo(),
                kart.getModel(),
                TrackDict.kartModelName(kart.getModel()),
                kart.getEngineCc(),
                kart.getMaxPower(),
                kart.getStatus(),
                TrackDict.kartStatusName(kart.getStatus()),
                kart.getTotalKm(),
                kart.getTotalHours(),
                kart.getLastServiceDate(),
                kart.getServiceIntervalH(),
                due,
                due ? "已超保养间隔，建议立即保养" : "距下次保养约 " + hoursLeft(kart) + " 小时",
                sessionNo,
                kart.getUpdatedAt());
    }

    /** 到期判定：上次保养至今按日均 8 小时折算的累计小时 ≥ 保养间隔 */
    boolean isServiceDue(Kart kart) {
        if (kart.getLastServiceDate() == null) {
            return true;
        }
        long days = ChronoUnit.DAYS.between(kart.getLastServiceDate(), LocalDate.now());
        return days * TrackDict.DAILY_HOURS >= kart.getServiceIntervalH();
    }

    long hoursLeft(Kart kart) {
        if (kart.getLastServiceDate() == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(kart.getLastServiceDate(), LocalDate.now());
        long left = kart.getServiceIntervalH() - days * TrackDict.DAILY_HOURS;
        return left < 0 ? 0 : left;
    }
}
