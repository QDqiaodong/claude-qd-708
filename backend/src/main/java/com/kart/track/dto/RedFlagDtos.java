package com.kart.track.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块六（红旗事件处置台）入参。 */
public final class RedFlagDtos {

    private RedFlagDtos() {
    }

    /** 值班员发起红旗 */
    @Data
    public static class RaiseReq {

        @NotBlank(message = "红旗原因不能为空")
        private String reason;

        /** 发生时刻可空，默认服务器当前时刻（保证一定有发生时刻） */
        private String occurredAt;
    }

    /** 值班员填写车辆核查结论 */
    @Data
    public static class CheckReq {

        @NotBlank(message = "核查结论不能为空")
        private String result;

        /** 备注；选择「需要维修」时应填故障描述 */
        private String note;

        /** 页面上看到的版本号；过期页面拿着旧版本提交会被拒 */
        private Integer version;
    }

    /** 主管裁定一条红旗期间圈速：CONFIRMED 确认有效 / VOIDED 作废 */
    @Data
    public static class AdjudicateReq {

        @NotBlank(message = "裁定结果不能为空")
        private String adjudication;
    }

    /** 主管恢复比赛 / 强制终止（version 防过期页面重复处置） */
    @Data
    public static class ResolveReq {

        /** 页面上看到的红旗事件版本号 */
        private Integer version;

        private String note;
    }
}
