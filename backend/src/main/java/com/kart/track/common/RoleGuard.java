package com.kart.track.common;

/**
 * 处置台角色与权限收口。
 *
 * <p>系统不做登录态，前端顶栏切换当前身份，axios 统一带 {@code X-Operator-Role} 请求头：
 * <ul>
 *   <li>DUTY 值班员：只能发起红旗、填写车辆核查；</li>
 *   <li>SUPERVISOR 主管：可以裁定红旗圈速、执行恢复 / 强制终止（也只读核查进度）。</li>
 * </ul>
 * 查询接口不设限；写操作在这里显式校验，越权返回明确中文提示而不是静默成功。
 */
public final class RoleGuard {

    /** 值班员 */
    public static final String DUTY = "DUTY";
    /** 主管 */
    public static final String SUPERVISOR = "SUPERVISOR";

    private RoleGuard() {
    }

    /** 归一化角色；未带身份头默认按值班员（只能看到发起/核查入口，处置按钮会被前端隐藏） */
    public static String normalize(String role) {
        return SUPERVISOR.equalsIgnoreCase(role) ? SUPERVISOR : DUTY;
    }

    public static boolean isSupervisor(String role) {
        return SUPERVISOR.equals(normalize(role));
    }

    /** 只有值班员能做：发起红旗、填写核查 */
    public static void requireDuty(String role) {
        if (isSupervisor(role)) {
            throw new BizException("该操作只能由值班员执行，主管无权发起红旗或填写车辆核查");
        }
    }

    /** 只有主管能做：裁定圈速、恢复比赛、强制终止 */
    public static void requireSupervisor(String role) {
        if (!isSupervisor(role)) {
            throw new BizException("该操作只能由主管执行：值班员仅可发起红旗与填写车辆核查");
        }
    }
}
