package com.kart.track.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.SessionDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.RaceSessionRepository;

/** 模块三：场次（待开赛 → 进行中 → 已结束 / 已取消）。 */
@Service
public class SessionService {

    private final RaceSessionRepository sessionRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final LapRecordRepository lapRecordRepository;

    public SessionService(RaceSessionRepository sessionRepository,
                          DriverRepository driverRepository,
                          KartRepository kartRepository,
                          LapRecordRepository lapRecordRepository) {
        this.sessionRepository = sessionRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.lapRecordRepository = lapRecordRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.SessionView> list() {
        List<Views.SessionView> list = new ArrayList<>();
        for (RaceSession session : sessionRepository.findAllByOrderByStartTimeDesc()) {
            list.add(toView(session));
        }
        return list;
    }

    @Transactional
    public Views.SessionView create(SessionDtos.SessionSaveReq req) {
        if (!TrackDict.isValidTrackConfig(req.getTrackConfig())) {
            throw new BizException("赛道配置只能是 半场/全场/夜间");
        }
        if (sessionRepository.existsBySessionNo(req.getSessionNo())) {
            throw new BizException("场次号 " + req.getSessionNo() + " 已存在");
        }
        int max = req.getMaxKarts() == null ? TrackDict.configMaxKart(req.getTrackConfig()) : req.getMaxKarts();
        if (max > TrackDict.configMaxKart(req.getTrackConfig())) {
            throw new BizException(TrackDict.trackConfigName(req.getTrackConfig()) + "最多 " + TrackDict.configMaxKart(req.getTrackConfig()) + " 台车");
        }
        RaceSession session = new RaceSession();
        session.setSessionNo(req.getSessionNo());
        session.setTrackConfig(req.getTrackConfig());
        session.setGroupCode(req.getGroupCode());
        session.setMaxKarts(max);
        session.setSignedCount(0);
        session.setStatus("PENDING");
        session.setStartTime(null);
        session.setEndTime(null);
        return toView(sessionRepository.save(session));
    }

    /** 开赛：待开赛 → 进行中 */
    @Transactional
    public Views.SessionView start(Long id) {
        RaceSession session = sessionRepository.findById(id).orElseThrow(() -> new BizException("场次不存在"));
        if (!"PENDING".equals(session.getStatus())) {
            throw new BizException("只有「待开赛」的场次可以开赛");
        }
        if (session.getSignedCount() <= 0) {
            throw new BizException("还没有人报名，不能开赛");
        }
        session.setStatus("RUNNING");
        session.setStartTime(LocalDateTime.now());
        return toView(sessionRepository.save(session));
    }

    /** 结束：进行中 → 已结束，顺带收车 + 给每位报名车手累计场次 +1 */
    @Transactional
    public Views.SessionView finish(Long id) {
        RaceSession session = sessionRepository.findById(id).orElseThrow(() -> new BizException("场次不存在"));
        if (!"RUNNING".equals(session.getStatus())) {
            throw new BizException("只有「进行中」的场次可以结束");
        }
        session.setStatus("FINISHED");
        session.setEndTime(LocalDateTime.now());
        sessionRepository.save(session);

        for (Driver driver : driverRepository.findAllBySessionIdOrderByBestLapMsAsc(session.getId())) {
            driver.setTotalSessions(driver.getTotalSessions() + 1);
            driver.setSessionId(null);
            driverRepository.save(driver);
        }
        List<Long> onTrackKartIds = lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId())
                .stream().map(LapRecord::getKartId).distinct().toList();
        for (Long kartId : onTrackKartIds) {
            Kart kart = kartRepository.findById(kartId).orElse(null);
            if (kart != null && "ON_TRACK".equals(kart.getStatus())) {
                kart.setStatus("AVAILABLE");
                kart.setUpdatedAt(LocalDateTime.now());
                kartRepository.save(kart);
            }
        }
        return toView(session);
    }

    /** 取消：待开赛 → 已取消，释放已报名车手 */
    @Transactional
    public Views.SessionView cancel(Long id) {
        RaceSession session = sessionRepository.findById(id).orElseThrow(() -> new BizException("场次不存在"));
        if (!"PENDING".equals(session.getStatus())) {
            throw new BizException("只有「待开赛」的场次可以取消");
        }
        session.setStatus("CANCELED");
        sessionRepository.save(session);
        for (Driver driver : driverRepository.findAllBySessionIdOrderByBestLapMsAsc(session.getId())) {
            driver.setSessionId(null);
            driverRepository.save(driver);
        }
        return toView(session);
    }

    public Views.SessionView toView(RaceSession session) {
        List<LapRecord> laps = lapRecordRepository.findBySessionIdOrderByLapTimeMsAsc(session.getId());
        long elapsed = 0;
        if (session.getStartTime() != null) {
            LocalDateTime end = "RUNNING".equals(session.getStatus()) ? LocalDateTime.now() : session.getEndTime();
            if (end == null) {
                end = LocalDateTime.now();
            }
            elapsed = ChronoUnit.SECONDS.between(session.getStartTime(), end);
            if (elapsed < 0) {
                elapsed = 0;
            }
        }
        Integer fastest = null;
        for (LapRecord lap : laps) {
            if ("VALID".equals(lap.getValid())) {
                fastest = lap.getLapTimeMs();
                break;
            }
        }
        return new Views.SessionView(
                session.getId(),
                session.getSessionNo(),
                session.getStartTime(),
                session.getEndTime(),
                session.getTrackConfig(),
                TrackDict.trackConfigName(session.getTrackConfig()),
                session.getGroupCode(),
                session.getMaxKarts(),
                session.getSignedCount(),
                session.getStatus(),
                TrackDict.sessionStatusName(session.getStatus()),
                elapsed,
                laps.size(),
                TrackDict.formatLap(fastest));
    }
}
