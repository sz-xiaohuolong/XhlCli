package com.xhlcli.parallel;

import java.util.Objects;

/**
 * 声明工具或任务对特定系统资源的访问方式与类型
 */
public record ResourceAccess(String resourceKey, AccessType accessType) {

    public enum AccessType {
        READ,       // 只读访问，可与其他读访问共享
        WRITE,      // 写入修改访问，对同一资源互斥
        EXCLUSIVE   // 全局排他访问，不允许任何形式的并发
    }

    public ResourceAccess {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(accessType, "accessType");
    }

    public static ResourceAccess read(String resourceKey) {
        return new ResourceAccess(resourceKey, AccessType.READ);
    }

    public static ResourceAccess write(String resourceKey) {
        return new ResourceAccess(resourceKey, AccessType.WRITE);
    }

    public static ResourceAccess exclusive(String resourceKey) {
        return new ResourceAccess(resourceKey, AccessType.EXCLUSIVE);
    }

    /**
     * 判断两个资源访问是否存在冲突
     */
    public boolean conflictsWith(ResourceAccess other) {
        if (other == null) {
            return false;
        }
        if (this.accessType == AccessType.EXCLUSIVE || other.accessType == AccessType.EXCLUSIVE) {
            return true;
        }
        if (this.resourceKey.equalsIgnoreCase(other.resourceKey)) {
            // 同一资源，只要有一方是写入，即产生读写或写写冲突
            return this.accessType == AccessType.WRITE || other.accessType == AccessType.WRITE;
        }
        return false;
    }
}
