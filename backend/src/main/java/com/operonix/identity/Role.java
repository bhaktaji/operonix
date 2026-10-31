package com.operonix.identity;

public enum Role {
    OWNER,
    ADMIN,
    MANAGER,
    TECHNICIAN;

    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isManagerOrAbove() {
        return this == OWNER || this == ADMIN || this == MANAGER;
    }
}
