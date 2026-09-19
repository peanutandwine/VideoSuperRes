package com.arthenica.ffmpegkit;

/**
 * Thin wrapper so callers don't have to tokenize commands themselves.
 */
public class FFmpegKit {
    public static final int RETURN_CODE_SUCCESS = 0;

    public static int execute(String command) {
        FFmpegKitConfig.enableNativeRedirection();
        String[] args = command.trim().split("\\s+");
        return FFmpegKitConfig.nativeFFmpegExecute(args);
    }

    public static String getVersion() {
        try { return FFmpegKitConfig.getNativeVersion(); }
        catch (Throwable t) { return "unknown"; }
    }

    public static String getFFmpegVersion() {
        try { return FFmpegKitConfig.getNativeFFmpegVersion(); }
        catch (Throwable t) { return "unknown"; }
    }
}
