package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.LapRecordDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.RaceSessionRepository;

/**
 * 模块四：圈速记录。
 *
 * <p>本模块的写链路全部走实体上的 Hibernate 自定义 SQL：
 * 新增走 {@code @SQLInsert}、改判走 {@code @SQLUpdate}、删除走 {@code @SQLDelete}，
 * Hibernate 不会为这三件事生成默认语句。
 */
@Service
public class LapRecordService {

    private final LapRecordRepository lapRecordRepository;
    private final RaceSessionRepository sessionRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;

    public LapRecordService(LapRecordRepository lapRecordRepository,
                            RaceSessionRepository sessionRepository,
                            DriverRepository driverRepository,
                            KartRepository kartRepository) {
        this.lapRecordRepository = lapRecordRepository;
        this.sessionRepository = sessionRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.LapRecordView> list(Long sessionId) {
        List<LapRecord> laps = sessionId == null
                ? lapRecordRepository.findAll()
                : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        Map<Long, String> driverName = new HashMap<>();
        for (Driver driver : driverRepository.findAll()) {
            driverName.put(driver.getId(), driver.getName());
        }
        Map<Long, String> kartNo = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            kartNo.put(kart.getId(), kart.getKartNo());
        }
        Map<Long, String> sessionNo = new HashMap<>();
        for (RaceSession session : sessionRepository.findAll()) {
            sessionNo.put(session.getId(), session.getSessionNo());
        }
        List<Views.LapRecordView> list = new ArrayList<>();
        for (LapRecord lap : laps) {
            list.add(new Views.LapRecordView(
                    lap.getId(),
                    lap.getSessionId(),
                    sessionNo.get(lap.getSessionId()),
                    lap.getDriverId(),
                    driverName.get(lap.getDriverId()),
                    lap.getKartId(),
                    kartNo.get(lap.getKartId()),
                    lap.getLapIndex(),
                    lap.getLapTimeMs(),
                    TrackDict.formatLap(lap.getLapTimeMs()),
                    lap.isFastest(),
                    lap.getValid(),
                    TrackDict.lapValidName(lap.getValid()),
                    lap.getCreatedAt()));
        }
        return list;
    }

    /**
     * 登记一个圈速：insert 语句来自 {@code LapRecord} 上的 {@code @SQLInsert}。
     * 若它刷新了本场最快圈，旧的最快圈行会被 {@code @SQLUpdate} 那句手写 update 复位。
     */
    @Transactional
    public Views.LapRecordView create(LapRecordDtos.LapSaveReq req) {
        RaceSession session = sessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"RUNNING".equals(session.getStatus())) {
            throw new BizException("只有「进行中」的场次才能登记圈速，当前为" + TrackDict.sessionStatusName(session.getStatus()));
        }
        Driver driver = driverRepository.findById(req.getDriverId()).orElseThrow(() -> new BizException("车手不存在"));
        Kart kart = kartRepository.findById(req.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));

        // 车型门槛：竞技车要中级以上驾照，双人车要初级以上，儿童车只给儿童组
        String needLicense = TrackDict.modelMinLicense(kart.getModel());
        if (TrackDict.licenseRank(driver.getLicenseLevel()) < TrackDict.licenseRank(needLicense)) {
            throw new BizException(TrackDict.kartModelName(kart.getModel()) + " 需要 "
                    + TrackDict.licenseName(needLicense) + "以上驾照，" + driver.getName() + " 为"
                    + TrackDict.licenseName(driver.getLicenseLevel()));
        }
        if ("KID".equals(kart.getModel()) && !"KID".equals(driver.getAgeGroup())) {
            throw new BizException("儿童车只允许儿童组车手驾驶");
        }
        if (!"ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 未在赛道上（当前" + TrackDict.kartStatusName(kart.getStatus()) + "），请先派车");
        }
        Integer ms = req.getLapTimeMs();
        if (ms == null || ms < TrackDict.MIN_LAP_MS || ms > TrackDict.MAX_LAP_MS) {
            throw new BizException("圈速应在 " + TrackDict.MIN_LAP_MS / 1000 + " 秒 ~ "
                    + TrackDict.MAX_LAP_MS / 1000 + " 秒之间");
        }
        String valid = req.getValid() == null || req.getValid().isBlank() ? "VALID" : req.getValid();
        if (!TrackDict.isValidLapValid(valid)) {
            throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
        }

        int lapIndex = (int) lapRecordRepository.findByDriverIdOrderByLapTimeMsAsc(driver.getId()).stream()
                .filter(lap -> lap.getSessionId().equals(session.getId()))
                .count() + 1;

        LapRecord lap = new LapRecord();
        lap.setSessionId(session.getId());
        lap.setDriverId(driver.getId());
        lap.setKartId(kart.getId());
        lap.setLapIndex(lapIndex);
        lap.setLapTimeMs(ms);
        lap.setValid(valid);
        lap.setCreatedAt(LocalDateTime.now());

        boolean fastest = false;
        if ("VALID".equals(valid)) {
            Integer currentBest = null;
            for (LapRecord old : lapRecordRepository.findBySessionIdAndValidOrderByLapTimeMsAsc(session.getId(), "VALID")) {
                currentBest = old.getLapTimeMs();
                break;
            }
            fastest = currentBest == null || ms < currentBest;
            if (fastest && currentBest != null) {
                // 旧最快圈复位：走 @SQLUpdate 手写 update
                for (LapRecord old : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId())) {
                    if (old.isFastest()) {
                        old.setFastest(false);
                        lapRecordRepository.save(old);
                    }
                }
            }
        }
        lap.setFastest(fastest);

        // ↓↓↓ 真正落库的一句：Hibernate 用 @SQLInsert 里手写好的 insert，不再自己生成
        LapRecord saved = lapRecordRepository.save(lap);

        if ("VALID".equals(valid)) {
            if (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0 || ms < driver.getBestLapMs()) {
                driver.setBestLapMs(ms);
                driverRepository.save(driver);
            }
            BigDecimal km = TrackDict.configLapKm(session.getTrackConfig());
            kart.setTotalKm(kart.getTotalKm().add(km));
            kart.setTotalHours(kart.getTotalHours().add(BigDecimal.valueOf(ms / 3600000.0)));
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }
        return list(session.getId()).stream()
                .filter(v -> v.id().equals(saved.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("圈速写入失败"));
    }

    /** 改判：有效 ↔ 无效-出界 / 无效-碰撞（走 @SQLUpdate） */
    @Transactional
    public Views.LapRecordView setValid(Long id, LapRecordDtos.LapValidReq req) {
        LapRecord lap = lapRecordRepository.findById(id).orElseThrow(() -> new BizException("圈速记录不存在"));
        if (!TrackDict.isValidLapValid(req.getValid())) {
            throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
        }
        lap.setValid(req.getValid());
        if (!"VALID".equals(req.getValid())) {
            lap.setFastest(false);
        }
        lapRecordRepository.save(lap);
        return list(lap.getSessionId()).stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException("圈速记录不存在"));
    }

    /** 删除一条误录（走 @SQLDelete） */
    @Transactional
    public void delete(Long id) {
        LapRecord lap = lapRecordRepository.findById(id).orElseThrow(() -> new BizException("圈速记录不存在"));
        lapRecordRepository.delete(lap);
    }
}
