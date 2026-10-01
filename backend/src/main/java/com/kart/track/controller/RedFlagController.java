package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.common.HeaderName;
import com.kart.track.common.RoleGuard;
import com.kart.track.dto.RedFlagDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.RedFlagService;

import jakarta.validation.Valid;

/**
 * 模块六：红旗事件处置台。
 *
 * <p>身份由请求头携带（前端顶栏切换身份后 axios 统一注入）：
 * {@code X-Operator-Role}=DUTY/SUPERVISOR、{@code X-Operator-Name}=姓名。
 * 值班员只能发起红旗 + 填核查；主管只能裁定圈速 + 恢复/终止；越权写操作直接 400 拒绝。
 */
@RestController
@RequestMapping("/api/red-flags")
public class RedFlagController {

    private final RedFlagService redFlagService;

    public RedFlagController(RedFlagService redFlagService) {
        this.redFlagService = redFlagService;
    }

    /** 当前生效红旗（处置台主屏用），无则 data=null */
    @GetMapping("/active")
    public ApiResponse<Views.RedFlagEventView> active(@RequestParam("sessionId") Long sessionId) {
        return ApiResponse.success(redFlagService.active(sessionId));
    }

    /** 场次详情回看历史红旗事件 */
    @GetMapping
    public ApiResponse<List<Views.RedFlagHistoryView>> history(@RequestParam("sessionId") Long sessionId) {
        return ApiResponse.success(redFlagService.history(sessionId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.RedFlagEventView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(redFlagService.get(id));
    }

    /** 值班员发起红旗（并发重复请求返回同一事件、同一套清单） */
    @PostMapping("/sessions/{sessionId}/raise")
    public ApiResponse<Views.RedFlagEventView> raise(@PathVariable("sessionId") Long sessionId,
                                                     @Valid @RequestBody RedFlagDtos.RaiseReq req,
                                                     @RequestHeader(value = "X-Operator-Role", required = false) String role,
                                                     @RequestHeader(value = "X-Operator-Name", required = false) String name) {
        RoleGuard.requireDuty(role);
        return ApiResponse.success("红旗已发起，本场进入红旗处理中", redFlagService.raise(sessionId, req, HeaderName.decode(name)));
    }

    /** 值班员填写一台车的核查结论 */
    @PostMapping("/{eventId}/checks/{checkId}")
    public ApiResponse<Views.RedFlagEventView> submitCheck(@PathVariable("eventId") Long eventId,
                                                           @PathVariable("checkId") Long checkId,
                                                           @Valid @RequestBody RedFlagDtos.CheckReq req,
                                                           @RequestHeader(value = "X-Operator-Role", required = false) String role,
                                                           @RequestHeader(value = "X-Operator-Name", required = false) String name) {
        RoleGuard.requireDuty(role);
        return ApiResponse.success("核查结论已提交", redFlagService.submitCheck(eventId, checkId, req, HeaderName.decode(name)));
    }

    /** 主管裁定一条红旗期间圈速 */
    @PostMapping("/{eventId}/laps/{lapId}/adjudicate")
    public ApiResponse<Views.RedFlagEventView> adjudicate(@PathVariable("eventId") Long eventId,
                                                          @PathVariable("lapId") Long lapId,
                                                          @Valid @RequestBody RedFlagDtos.AdjudicateReq req,
                                                          @RequestHeader(value = "X-Operator-Role", required = false) String role,
                                                          @RequestHeader(value = "X-Operator-Name", required = false) String name) {
        RoleGuard.requireSupervisor(role);
        return ApiResponse.success("圈速已裁定", redFlagService.adjudicate(eventId, lapId, req, HeaderName.decode(name)));
    }

    /** 主管恢复比赛 */
    @PostMapping("/{eventId}/resume")
    public ApiResponse<Views.RedFlagEventView> resume(@PathVariable("eventId") Long eventId,
                                                      @Valid @RequestBody(required = false) RedFlagDtos.ResolveReq req,
                                                      @RequestHeader(value = "X-Operator-Role", required = false) String role,
                                                      @RequestHeader(value = "X-Operator-Name", required = false) String name) {
        RoleGuard.requireSupervisor(role);
        return ApiResponse.success("比赛已恢复，可继续记圈", redFlagService.resume(eventId, resolve(req), HeaderName.decode(name)));
    }

    /** 主管强制终止（一次性收口） */
    @PostMapping("/{eventId}/terminate")
    public ApiResponse<Views.RedFlagEventView> terminate(@PathVariable("eventId") Long eventId,
                                                         @Valid @RequestBody(required = false) RedFlagDtos.ResolveReq req,
                                                         @RequestHeader(value = "X-Operator-Role", required = false) String role,
                                                         @RequestHeader(value = "X-Operator-Name", required = false) String name) {
        RoleGuard.requireSupervisor(role);
        return ApiResponse.success("场次已强制终止，车手与可用车辆已释放", redFlagService.terminate(eventId, resolve(req), HeaderName.decode(name)));
    }

    private RedFlagDtos.ResolveReq resolve(RedFlagDtos.ResolveReq req) {
        return req == null ? new RedFlagDtos.ResolveReq() : req;
    }
}
