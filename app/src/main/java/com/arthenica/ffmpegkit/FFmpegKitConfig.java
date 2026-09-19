package com.arthenica.ffmpegkit;

/**
 * Minimal JNI bridge over libffmpegkit.so extracted from ffmpeg-kit.
 * Only the methods we need for remuxing are declared; the native library
 * was compiled with these exact JNI symbols.
 */
public class FFmpegKitConfig {

    static {
        // Load in dependency order
        System.loadLibrary("avutil");
        System.loadLibrary("swscale");
        System.loadLibrary("swresample");
        System.loadLibrary("avcodec");
        System.loadLibrary("avformat");
        System.loadLibrary("avfilter");
        System.loadLibrary("avdevice");
        System.loadLibrary("ffmpegkit_abidetect");
        System.loadLibrary("ffmpegkit");
    }

    public static native int nativeFFmpegExecute(String[] command);
    public static native int nativeFFprobeExecute(String[] command);
    public static native void nativeFFmpegCancel();

    public static native String getNativeFFmpegVersion();
    public static native String getNativeVersion();
    public static native String getNativeBuildDate();

    public static native void setNativeLogLevel(int level);
    public static native int getNativeLogLevel();

    public static native void enableNativeRedirection();
    public static native void disableNativeRedirection();

    public static native void setNativeEnvironmentVariable(String key, String value);
    public static native void ignoreNativeSignal(int signal);
    public static native boolean messagesInTransmit(long id);
    public static native String registerNewNativeFFmpegPipe(String name);
}
