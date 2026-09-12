package com.claw.service;

final class AiMonitorContextHolder {
    private static final ThreadLocal<AiMonitorService.RequestTrace> HOLDER = new ThreadLocal<>();

    private AiMonitorContextHolder() {
    }

    static AiMonitorService.RequestTrace get() {
        return HOLDER.get();
    }

    static void set(AiMonitorService.RequestTrace trace) {
        if (trace == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(trace);
    }

    static void clear() {
        HOLDER.remove();
    }
}
