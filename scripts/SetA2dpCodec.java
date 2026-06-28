import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SetA2dpCodec {
    private static final int CODEC_APTX_ADAPTIVE = 9;
    private static final int PRIORITY_HIGHEST = BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST;
    private static final int SAMPLE_RATE_44100 = BluetoothCodecConfig.SAMPLE_RATE_44100;
    private static final int SAMPLE_RATE_48000 = BluetoothCodecConfig.SAMPLE_RATE_48000;
    private static final int SAMPLE_RATE_88200 = BluetoothCodecConfig.SAMPLE_RATE_88200;
    private static final int SAMPLE_RATE_96000 = BluetoothCodecConfig.SAMPLE_RATE_96000;
    private static final int SAMPLE_RATE_176400 = BluetoothCodecConfig.SAMPLE_RATE_176400;
    private static final int SAMPLE_RATE_192000 = BluetoothCodecConfig.SAMPLE_RATE_192000;
    private static final int BITS_16 = BluetoothCodecConfig.BITS_PER_SAMPLE_16;
    private static final int BITS_24 = BluetoothCodecConfig.BITS_PER_SAMPLE_24;
    private static final int BITS_32 = BluetoothCodecConfig.BITS_PER_SAMPLE_32;
    private static final int MONO = BluetoothCodecConfig.CHANNEL_MODE_MONO;
    private static final int STEREO = BluetoothCodecConfig.CHANNEL_MODE_STEREO;

    public static void main(String[] args) throws Exception {
        exemptHiddenApis();
        initializeBluetoothFramework();

        Looper.prepareMainLooper();
        Context context = getSystemContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IllegalStateException("BluetoothAdapter.getDefaultAdapter() returned null");
        }

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                int exitCode = 0;
                try {
                    runWithProxy((BluetoothA2dp) proxy);
                } catch (Throwable throwable) {
                    throwable.printStackTrace(System.out);
                    exitCode = 1;
                } finally {
                    try {
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    } catch (Throwable ignored) {
                    }
                    System.exit(exitCode);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                System.out.println("A2DP service disconnected");
            }
        };

        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
            throw new IllegalStateException("getProfileProxy(A2DP) returned false");
        }

        Looper.loop();
    }

    private static void runWithProxy(BluetoothA2dp a2dp) throws Exception {
        List<DeviceChoice> choices = collectConnectedDevices(a2dp);
        if (choices.isEmpty()) {
            throw new IllegalStateException("No connected A2DP audio device found");
        }

        DeviceChoice target = chooseBestDevice(choices);
        if (target == null || target.bestCodec == null) {
            throw new IllegalStateException("Connected device does not report selectable A2DP codecs");
        }

        BluetoothDevice device = target.device;
        BluetoothCodecStatus before = target.status;
        BluetoothCodecConfig config = target.bestCodec.config;
        System.out.println("Device: " + safeName(device) + " " + device.getAddress());
        System.out.println("Before: " + describe(before));
        System.out.println("Requested: " + describe(config));
        System.out.println("Selectable: " + target.bestCodec.selectable);
        System.out.println("Explicit lossless: " + target.bestCodec.explicitLossless);

        Method setPreference = BluetoothA2dp.class.getDeclaredMethod(
                "setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
        setPreference.setAccessible(true);
        Object result = setPreference.invoke(a2dp, device, config);
        System.out.println("setCodecConfigPreference result: " + result);

        Thread.sleep(3000);
        BluetoothCodecStatus after = getCodecStatus(a2dp, device);
        System.out.println("After: " + describe(after));
    }

    private static List<DeviceChoice> collectConnectedDevices(BluetoothA2dp a2dp) throws Exception {
        List<DeviceChoice> choices = new ArrayList<>();
        List<BluetoothDevice> connected = a2dp.getConnectedDevices();
        for (BluetoothDevice device : connected) {
            BluetoothCodecStatus status = getCodecStatus(a2dp, device);
            choices.add(new DeviceChoice(device, status, chooseBestCodec(status)));
        }
        return choices;
    }

    private static DeviceChoice chooseBestDevice(List<DeviceChoice> choices) {
        DeviceChoice best = null;
        for (DeviceChoice choice : choices) {
            if (choice.bestCodec == null) {
                continue;
            }
            if (best == null || choice.bestCodec.score > best.bestCodec.score) {
                best = choice;
            }
        }
        return best;
    }

    private static CodecChoice chooseBestCodec(BluetoothCodecStatus status) throws Exception {
        if (status == null) {
            return null;
        }

        List<BluetoothCodecConfig> selectable = status.getCodecsSelectableCapabilities();
        if (selectable == null || selectable.isEmpty()) {
            BluetoothCodecConfig current = status.getCodecConfig();
            return current == null ? null : new CodecChoice(current, scoreCodec(current), false,
                    isExplicitLossless(current));
        }

        CodecChoice best = null;
        for (BluetoothCodecConfig capability : selectable) {
            BluetoothCodecConfig requested = buildRequestedCodec(capability);
            boolean selectableRequested = status.isCodecConfigSelectable(requested);
            if (!selectableRequested && requested != capability) {
                selectableRequested = status.isCodecConfigSelectable(capability);
            }
            int score = scoreCodec(capability);
            if (!selectableRequested) {
                score -= 100000;
            }
            CodecChoice candidate = new CodecChoice(requested, score, selectableRequested,
                    isExplicitLossless(capability));
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private static BluetoothCodecConfig buildRequestedCodec(BluetoothCodecConfig capability)
            throws Exception {
        if (usesExtendedCodecObject(capability)) {
            return capability;
        }

        return newCodecConfig(
                capability.getCodecType(),
                PRIORITY_HIGHEST,
                bestSampleRate(capability.getSampleRate()),
                bestBitsPerSample(capability.getBitsPerSample()),
                bestChannelMode(capability.getChannelMode()),
                capability.getCodecSpecific1(),
                capability.getCodecSpecific2(),
                capability.getCodecSpecific3(),
                capability.getCodecSpecific4());
    }

    private static boolean usesExtendedCodecObject(BluetoothCodecConfig config) {
        String text = codecSearchText(config);
        int codecType = config.getCodecType();
        return codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID
                || isExplicitLossless(config)
                || (extendedCodecName(config) != null
                && codecType != CODEC_APTX_ADAPTIVE
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3
                && codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS)
                || text.contains("lhdc")
                || text.contains("lhac");
    }

    private static BluetoothCodecStatus getCodecStatus(BluetoothA2dp a2dp, BluetoothDevice device) throws Exception {
        Method method = BluetoothA2dp.class.getDeclaredMethod("getCodecStatus", BluetoothDevice.class);
        method.setAccessible(true);
        return (BluetoothCodecStatus) method.invoke(a2dp, device);
    }

    private static String describe(BluetoothCodecStatus status) {
        if (status == null || status.getCodecConfig() == null) {
            return "unknown";
        }
        return describe(status.getCodecConfig());
    }

    private static String describe(BluetoothCodecConfig config) {
        if (config == null) {
            return "unknown";
        }
        return codecName(config)
                + " / " + sampleRate(config.getSampleRate())
                + " / " + bits(config.getBitsPerSample())
                + " / " + channel(config.getChannelMode())
                + codecSpecific(config);
    }

    private static String codecName(BluetoothCodecConfig config) {
        String extendedName = extendedCodecName(config);
        if (extendedName != null && extendedName.length() > 0
                && !"invalid".equals(extendedName.toLowerCase(Locale.US))) {
            return extendedName;
        }
        return codecName(config.getCodecType());
    }

    private static String codecName(int codecType) {
        if (codecType == CODEC_APTX_ADAPTIVE) {
            return "aptX Adaptive";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) {
            return "SBC";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC) {
            return "AAC";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX) {
            return "aptX";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD) {
            return "aptX HD";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            return "LDAC";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3) {
            return "LC3";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS) {
            return "Opus";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
            return "extended codec";
        }
        return "codec " + codecType;
    }

    private static String sampleRate(int sampleRate) {
        if (sampleRate == BluetoothCodecConfig.SAMPLE_RATE_NONE) {
            return "default rate";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_44100, "44.1");
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_48000, "48");
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_88200, "88.2");
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_96000, "96");
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_176400, "176.4");
        known |= appendMask(builder, sampleRate, SAMPLE_RATE_192000, "192");
        if (builder.length() > 0 && (sampleRate & ~known) == 0) {
            return builder.append(" kHz").toString();
        }
        return "rate 0x" + Integer.toHexString(sampleRate);
    }

    private static String bits(int bits) {
        if (bits == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
            return "default bits";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendMask(builder, bits, BITS_16, "16");
        known |= appendMask(builder, bits, BITS_24, "24");
        known |= appendMask(builder, bits, BITS_32, "32");
        if (builder.length() > 0 && (bits & ~known) == 0) {
            return builder.append("-bit").toString();
        }
        return "bits 0x" + Integer.toHexString(bits);
    }

    private static String channel(int channel) {
        if (channel == BluetoothCodecConfig.CHANNEL_MODE_NONE) {
            return "default channel";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendMask(builder, channel, MONO, "mono");
        known |= appendMask(builder, channel, STEREO, "stereo");
        if (builder.length() > 0 && (channel & ~known) == 0) {
            return builder.toString();
        }
        return "channel 0x" + Integer.toHexString(channel);
    }

    private static int appendMask(StringBuilder builder, int mask, int value, String label) {
        if ((mask & value) == 0) {
            return 0;
        }
        if (builder.length() > 0) {
            builder.append("/");
        }
        builder.append(label);
        return value;
    }

    private static String safeName(BluetoothDevice device) {
        String name = device.getName();
        return name == null ? "Bluetooth device" : name;
    }

    private static int scoreCodec(BluetoothCodecConfig config) {
        String text = codecSearchText(config);
        int score;
        if (text.contains("lossless")) {
            score = 100000;
        } else if (config.getCodecType() == CODEC_APTX_ADAPTIVE
                || (text.contains("aptx") && text.contains("adaptive"))) {
            score = 90000;
        } else if (text.contains("lhdc") || text.contains("lhac")) {
            score = 85000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
                || text.contains("ldac")) {
            score = 80000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD
                || text.contains("aptx hd")) {
            score = 70000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX
                || text.contains("aptx")) {
            score = 60000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3
                || text.contains("lc3")) {
            score = 55000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS
                || text.contains("opus")) {
            score = 54000;
        } else if (config.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
                || text.contains("aac")) {
            score = 40000;
        } else {
            score = 10000;
        }

        score += sampleScore(bestSampleRate(config.getSampleRate()));
        score += bitsScore(bestBitsPerSample(config.getBitsPerSample()));
        if (bestChannelMode(config.getChannelMode()) == STEREO) {
            score += 20;
        }
        return score;
    }

    private static int bestSampleRate(int mask) {
        if ((mask & SAMPLE_RATE_192000) != 0) {
            return SAMPLE_RATE_192000;
        }
        if ((mask & SAMPLE_RATE_176400) != 0) {
            return SAMPLE_RATE_176400;
        }
        if ((mask & SAMPLE_RATE_96000) != 0) {
            return SAMPLE_RATE_96000;
        }
        if ((mask & SAMPLE_RATE_88200) != 0) {
            return SAMPLE_RATE_88200;
        }
        if ((mask & SAMPLE_RATE_48000) != 0) {
            return SAMPLE_RATE_48000;
        }
        if ((mask & SAMPLE_RATE_44100) != 0) {
            return SAMPLE_RATE_44100;
        }
        return mask;
    }

    private static int bestBitsPerSample(int mask) {
        if ((mask & BITS_32) != 0) {
            return BITS_32;
        }
        if ((mask & BITS_24) != 0) {
            return BITS_24;
        }
        if ((mask & BITS_16) != 0) {
            return BITS_16;
        }
        return mask;
    }

    private static int bestChannelMode(int mask) {
        if ((mask & STEREO) != 0) {
            return STEREO;
        }
        if ((mask & MONO) != 0) {
            return MONO;
        }
        return mask;
    }

    private static int sampleScore(int sampleRate) {
        if (sampleRate == SAMPLE_RATE_192000) {
            return 600;
        }
        if (sampleRate == SAMPLE_RATE_176400) {
            return 550;
        }
        if (sampleRate == SAMPLE_RATE_96000) {
            return 500;
        }
        if (sampleRate == SAMPLE_RATE_88200) {
            return 450;
        }
        if (sampleRate == SAMPLE_RATE_48000) {
            return 300;
        }
        if (sampleRate == SAMPLE_RATE_44100) {
            return 250;
        }
        return 0;
    }

    private static int bitsScore(int bits) {
        if (bits == BITS_32) {
            return 90;
        }
        if (bits == BITS_24) {
            return 70;
        }
        if (bits == BITS_16) {
            return 40;
        }
        return 0;
    }

    private static boolean isExplicitLossless(BluetoothCodecConfig config) {
        return codecSearchText(config).contains("lossless");
    }

    private static String codecSearchText(BluetoothCodecConfig config) {
        return (codecName(config) + " " + config.toString()).toLowerCase(Locale.US);
    }

    private static String extendedCodecName(BluetoothCodecConfig config) {
        try {
            Method getExtendedCodecType = BluetoothCodecConfig.class.getDeclaredMethod("getExtendedCodecType");
            Object codecType = getExtendedCodecType.invoke(config);
            if (codecType == null) {
                return null;
            }
            Method getCodecName = codecType.getClass().getDeclaredMethod("getCodecName");
            Object codecName = getCodecName.invoke(codecType);
            if (!(codecName instanceof String)) {
                return null;
            }
            String value = ((String) codecName).trim();
            return value.length() == 0 ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String codecSpecific(BluetoothCodecConfig config) {
        long specific1 = config.getCodecSpecific1();
        long specific2 = config.getCodecSpecific2();
        long specific3 = config.getCodecSpecific3();
        long specific4 = config.getCodecSpecific4();
        if (specific1 == 0L && specific2 == 0L && specific3 == 0L && specific4 == 0L) {
            return "";
        }
        return " / cs=[0x" + Long.toHexString(specific1)
                + ",0x" + Long.toHexString(specific2)
                + ",0x" + Long.toHexString(specific3)
                + ",0x" + Long.toHexString(specific4) + "]";
    }

    private static BluetoothCodecConfig newCodecConfig(
            int codecType,
            int codecPriority,
            int sampleRate,
            int bitsPerSample,
            int channelMode,
            long specific1,
            long specific2,
            long specific3,
            long specific4) throws Exception {
        Constructor<BluetoothCodecConfig> constructor = BluetoothCodecConfig.class.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class,
                long.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(codecType, codecPriority, sampleRate, bitsPerSample,
                channelMode, specific1, specific2, specific3, specific4);
    }

    private static Context getSystemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(activityThread);
    }

    private static void initializeBluetoothFramework() throws Exception {
        Class<?> initializerClass = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
        Method getBluetoothServiceManager = initializerClass.getDeclaredMethod("getBluetoothServiceManager");
        getBluetoothServiceManager.setAccessible(true);
        Object current = getBluetoothServiceManager.invoke(null);
        if (current != null) {
            return;
        }

        Class<?> serviceManagerClass = Class.forName("android.os.BluetoothServiceManager");
        Constructor<?> constructor = serviceManagerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object serviceManager = constructor.newInstance();

        Method setBluetoothServiceManager = initializerClass.getDeclaredMethod(
                "setBluetoothServiceManager", serviceManagerClass);
        setBluetoothServiceManager.setAccessible(true);
        setBluetoothServiceManager.invoke(null, serviceManager);
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    private static final class DeviceChoice {
        final BluetoothDevice device;
        final BluetoothCodecStatus status;
        final CodecChoice bestCodec;

        DeviceChoice(BluetoothDevice device, BluetoothCodecStatus status, CodecChoice bestCodec) {
            this.device = device;
            this.status = status;
            this.bestCodec = bestCodec;
        }
    }

    private static final class CodecChoice {
        final BluetoothCodecConfig config;
        final int score;
        final boolean selectable;
        final boolean explicitLossless;

        CodecChoice(BluetoothCodecConfig config, int score, boolean selectable, boolean explicitLossless) {
            this.config = config;
            this.score = score;
            this.selectable = selectable;
            this.explicitLossless = explicitLossless;
        }
    }
}
