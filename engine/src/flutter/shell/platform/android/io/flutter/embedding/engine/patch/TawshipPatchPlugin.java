// Copyright 2024 Tawship. All rights reserved.
// Platform channel plugin for Tawship patch management

package io.flutter.embedding.engine.patch;

import android.content.Context;
import android.util.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.util.PathUtils;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Flutter plugin for Tawship patch management.
 * 
 * Provides platform channel methods:
 * - downloadPatch: Download patch ZIP from URL
 * - applyPatch: Extract and apply patch
 * - getPatchInfo: Get information about current patch
 * - clearPatch: Remove applied patch
 */
public class TawshipPatchPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {
    private static final String TAG = "TawshipPatchPlugin";
    private static final String CHANNEL_NAME = "com.tawship/patch";

    private MethodChannel channel;
    private Context context;
    private TawshipPatchManager patchManager;
    private String appStoragePath;

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        appStoragePath = PathUtils.getFilesDir(context);
        patchManager = new TawshipPatchManager(context, appStoragePath);

        channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL_NAME);
        channel.setMethodCallHandler(this);
        Log.i(TAG, "TawshipPatchPlugin attached");
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
        patchManager = null;
        context = null;
        Log.i(TAG, "TawshipPatchPlugin detached");
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "downloadPatch":
                    handleDownloadPatch(call, result);
                    break;
                case "applyPatch":
                    handleApplyPatch(call, result);
                    break;
                case "getPatchInfo":
                    handleGetPatchInfo(call, result);
                    break;
                case "clearPatch":
                    handleClearPatch(call, result);
                    break;
                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling method call: " + call.method, e);
            result.error("ERROR", e.getMessage(), null);
        }
    }

    private void handleDownloadPatch(MethodCall call, MethodChannel.Result result) {
        String patchUrl = call.argument("url");
        String expectedChecksum = call.argument("checksum");

        if (patchUrl == null || patchUrl.isEmpty()) {
            result.error("INVALID_ARGUMENT", "Patch URL is required", null);
            return;
        }

        try {
            String patchFilePath = patchManager.downloadPatch(
                    patchUrl,
                    expectedChecksum,
                    (progress) -> {
                        // Send progress updates to Flutter
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("progress", progress);
                        channel.invokeMethod("onDownloadProgress", progressData);
                    });

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("path", patchFilePath);
            result.success(response);
        } catch (Exception e) {
            Log.e(TAG, "Failed to download patch", e);
            result.error("DOWNLOAD_FAILED", e.getMessage(), null);
        }
    }

    private void handleApplyPatch(MethodCall call, MethodChannel.Result result) {
        String patchFilePath = call.argument("path");
        String aotLibraryName = call.argument("aotLibraryName");

        if (patchFilePath == null || patchFilePath.isEmpty()) {
            result.error("INVALID_ARGUMENT", "Patch file path is required", null);
            return;
        }

        if (aotLibraryName == null || aotLibraryName.isEmpty()) {
            aotLibraryName = "libapp.so"; // Default
        }

        try {
            boolean applied = patchManager.applyPatch(patchFilePath, aotLibraryName);

            if (applied) {
                // Cleanup old patches
                patchManager.cleanupOldPatches();

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Patch applied successfully. Restart app to load new code.");
                result.success(response);
            } else {
                result.error("APPLY_FAILED", "Failed to apply patch", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply patch", e);
            result.error("APPLY_FAILED", e.getMessage(), null);
        }
    }

    private void handleGetPatchInfo(MethodCall call, MethodChannel.Result result) {
        try {
            String cpuArch = System.getProperty("os.arch");
            String archDir = mapCpuArchToFlutterArch(cpuArch);

            Map<String, Object> info = new HashMap<>();
            info.put("architecture", archDir);
            info.put("cpuArch", cpuArch);

            // Check if patched libapp.so exists
            if (archDir != null) {
                File patchLibDir = new File(appStoragePath, "tawship/lib/" + archDir);
                File libappSo = new File(patchLibDir, "libapp.so");

                info.put("hasPatch", libappSo.exists());
                if (libappSo.exists()) {
                    info.put("patchPath", libappSo.getAbsolutePath());
                    info.put("patchSize", libappSo.length());
                    info.put("patchModified", libappSo.lastModified());
                }
            } else {
                info.put("hasPatch", false);
            }

            result.success(info);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get patch info", e);
            result.error("ERROR", e.getMessage(), null);
        }
    }

    private void handleClearPatch(MethodCall call, MethodChannel.Result result) {
        try {
            String cpuArch = System.getProperty("os.arch");
            String archDir = mapCpuArchToFlutterArch(cpuArch);

            if (archDir != null) {
                File patchLibDir = new File(appStoragePath, "tawship/lib/" + archDir);
                File libappSo = new File(patchLibDir, "libapp.so");

                if (libappSo.exists()) {
                    boolean deleted = libappSo.delete();
                    if (deleted) {
                        result.success(true);
                    } else {
                        result.error("DELETE_FAILED", "Failed to delete patched libapp.so", null);
                    }
                } else {
                    result.success(true); // Already cleared
                }
            } else {
                result.error("UNKNOWN_ARCH", "Unknown CPU architecture", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear patch", e);
            result.error("ERROR", e.getMessage(), null);
        }
    }

    private String mapCpuArchToFlutterArch(String cpuArch) {
        switch (cpuArch.toLowerCase()) {
            case "aarch64":
                return "arm64-v8a";
            case "armv7l":
            case "arm":
                return "armeabi-v7a";
            case "x86_64":
                return "x86_64";
            case "x86":
                return "x86";
            default:
                return null;
        }
    }
}
