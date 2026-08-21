package com.yusd.pixel2dface;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();
        Bundle result = new Bundle();
        if ("state".equals(method) && getContext() != null) {
            TemplateStore.recordHookHeartbeat(getContext());
            boolean enabled = TemplateStore.isEnabled(getContext());
            boolean enrolled = TemplateStore.isEnrolled(getContext());
            long lockoutUntil = TemplateStore.getLockoutUntil(getContext());
            int animationStyle = TemplateStore.getAnimationStyle(getContext());
            result.putBoolean("enabled", enabled);
            result.putBoolean("enrolled", enrolled);
            result.putLong("lockout_until", lockoutUntil);
            result.putInt("animation_style", animationStyle);
            return result;
        }
        if ("heartbeat".equals(method) && getContext() != null) {
            String compatibility = buildCompatibilityReport(extras);
            TemplateStore.recordHookHeartbeat(getContext(), compatibility);
            result.putBoolean("ok", true);
            return result;
        }
        if ("authorize_unlock".equals(method) && getContext() != null) {
            result.putBoolean("ok", TemplateStore.authorizeUnlockSession(getContext(), arg));
            return result;
        }
        throw new IllegalArgumentException("Unsupported method: " + method);
    }

    private static String buildCompatibilityReport(Bundle extras) {
        if (extras == null) {
            return null;
        }
        String platform = extras.getString("platform", "未知平台");
        String authentication = extras.getString(
                "authentication_adapter", "尚未运行验证");
        boolean wakeMethod = extras.getBoolean("wake_method", false);
        boolean powerBroadcast = extras.getBoolean("power_broadcast", false);
        boolean overlay = extras.getBoolean("native_overlay", false);
        boolean occlusion = extras.getBoolean("occlusion_guard", false);
        return platform
                + "\n唤醒适配：" + capability(wakeMethod || powerBroadcast)
                + " · 认证适配：" + authentication
                + "\n锁屏动画：" + capability(overlay)
                + " · 相机宿主保护：" + capability(occlusion);
    }

    private static String capability(boolean available) {
        return available ? "可用" : "不可用（安全降级）";
    }

    private void enforceAllowedCaller() {
        if (getContext() == null) {
            throw new SecurityException("Provider is unavailable");
        }
        int callingUid = Binder.getCallingUid();
        if (callingUid == getContext().getApplicationInfo().uid) {
            return;
        }
        PackageManager packageManager = getContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages != null) {
            for (String packageName : packages) {
                if (Constants.SYSTEM_UI_PACKAGE.equals(packageName)) {
                    return;
                }
            }
        }
        throw new SecurityException("Caller is not SystemUI");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
