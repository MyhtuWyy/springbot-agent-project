package com.claw.service;

public final class ToolExecutionContextHolder {
    private static final ThreadLocal<UserSessionContext> CURRENT = new ThreadLocal<>();

    private ToolExecutionContextHolder() {
    }

    public static void set(UserSessionContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static UserSessionContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
