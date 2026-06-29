package com.larkconnect.agent.admin;

public final class AdminContext {
    private static final ThreadLocal<AdminPrincipal> CURRENT = new ThreadLocal<>();

    private AdminContext() {}

    static void set(AdminPrincipal principal) {
        CURRENT.set(principal);
    }

    public static AdminPrincipal current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
