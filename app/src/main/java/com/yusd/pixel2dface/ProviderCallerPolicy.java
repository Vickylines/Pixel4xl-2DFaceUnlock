package com.yusd.pixel2dface;

/** UID identity is authoritative; a package name supplied by a caller is never trusted. */
final class ProviderCallerPolicy {
    private ProviderCallerPolicy() { }

    static boolean allowed(int callingUid, int ownUid, int systemUiUid,
            boolean systemPackage, boolean platformSigned) {
        return callingUid >= 0 && (callingUid == ownUid
                || (callingUid == systemUiUid && systemPackage && platformSigned));
    }
}
