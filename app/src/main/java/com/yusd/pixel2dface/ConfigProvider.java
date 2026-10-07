package com.yusd.pixel2dface;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
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
        if (("state".equals(method) || "prepare_unlock".equals(method)) && getContext() != null) {
            TemplateStore.StateSnapshot state;
            if ("prepare_unlock".equals(method)) {
                if (extras != null && !extras.isEmpty()) {
                    throw new IllegalArgumentException("Preparation has no extras");
                }
                TemplateStore.UnlockPreparation preparation = TemplateStore.prepareUnlockSession(getContext(), arg);
                state = preparation.state;
                result.putBoolean("ok", preparation.authorized);
            } else {
                requireNoArguments(arg, extras);
                state = TemplateStore.readState(getContext());
            }
            TemplateStore.recordHookHeartbeat(getContext());
            result.putBoolean("enabled", state.enabled);
            result.putBoolean("enrolled", state.enrolled);
            result.putLong("lockout_until", state.lockoutUntil);
            result.putInt("animation_style", state.animationStyle);
            result.putBoolean("storage_available", state.storageAvailable);
            result.putLong("revision", state.revision);
            return result;
        }
        if ("heartbeat".equals(method) && getContext() != null) {
            if (arg != null) throw new IllegalArgumentException("Heartbeat has no argument");
            String compatibility = buildCompatibilityReport(extras);
            result.putBoolean("ok", TemplateStore.recordHookHeartbeat(getContext(), compatibility));
            return result;
        }
        if ("authorize_unlock".equals(method) && getContext() != null) {
            if (extras != null && !extras.isEmpty()) {
                throw new IllegalArgumentException("Authorization has no extras");
            }
            result.putBoolean("ok", TemplateStore.authorizeUnlockSession(getContext(), arg));
            return result;
        }
        throw new IllegalArgumentException("Unsupported provider method");
    }

    private static void requireNoArguments(String arg, Bundle extras) {
        if (arg != null || (extras != null && !extras.isEmpty())) {
            throw new IllegalArgumentException("State has no arguments");
        }
    }

    private static String buildCompatibilityReport(Bundle extras) {
        if (extras == null) {
            return null;
        }
        if (extras.size() > 6) throw new IllegalArgumentException("Too many compatibility fields");
        for (String key : extras.keySet()) {
            if (!("platform".equals(key) || "authentication_adapter".equals(key)
                    || "wake_method".equals(key) || "power_broadcast".equals(key)
                    || "native_overlay".equals(key) || "occlusion_guard".equals(key))) {
                throw new IllegalArgumentException("Unsupported compatibility field");
            }
        }
        String platform = text(extras, "platform", "未知平台");
        String authentication = text(extras, "authentication_adapter", "尚未运行验证");
        boolean wakeMethod = flag(extras, "wake_method");
        boolean powerBroadcast = flag(extras, "power_broadcast");
        boolean overlay = flag(extras, "native_overlay");
        boolean occlusion = flag(extras, "occlusion_guard");
        return platform
                + "\n唤醒适配：" + capability(wakeMethod || powerBroadcast)
                + " · 认证适配：" + authentication
                + "\n锁屏动画：" + capability(overlay)
                + " · 相机宿主保护：" + capability(occlusion);
    }

    @SuppressWarnings("deprecation")
    private static String text(Bundle extras, String key, String fallback) {
        Object value = extras.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid compatibility text");
        String text = ((String) value).trim();
        if (text.length() > 160) throw new IllegalArgumentException("Compatibility text too long");
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw new IllegalArgumentException("Invalid compatibility text");
            }
        }
        return text.isEmpty() ? fallback : text;
    }

    @SuppressWarnings("deprecation")
    private static boolean flag(Bundle extras, String key) {
        Object value = extras.get(key);
        if (value == null) return false;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid compatibility flag");
        return (Boolean) value;
    }

    private static String capability(boolean available) {
        return available ? "可用" : "不可用（安全降级）";
    }

    private void enforceAllowedCaller() {
        if (getContext() == null) {
            throw new SecurityException("Provider is unavailable");
        }
        int callingUid = Binder.getCallingUid();
        int ownUid = getContext().getApplicationInfo().uid;
        if (callingUid == ownUid) {
            return;
        }
        PackageManager packageManager = getContext().getPackageManager();
        try {
            ApplicationInfo systemUi = packageManager.getApplicationInfo(Constants.SYSTEM_UI_PACKAGE, 0);
            boolean systemPackage = (systemUi.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean platformSigned = packageManager.checkSignatures("android", Constants.SYSTEM_UI_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
            if (ProviderCallerPolicy.allowed(callingUid, ownUid, systemUi.uid,
                    systemPackage, platformSigned)) return;
        } catch (PackageManager.NameNotFoundException unavailable) {
            // A missing SystemUI package cannot grant access by name alone.
        }
        throw new SecurityException("Caller is not SystemUI");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceAllowedCaller();
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        enforceAllowedCaller();
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceAllowedCaller();
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceAllowedCaller();
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        enforceAllowedCaller();
        throw new UnsupportedOperationException();
    }
}
