package com.nowwwki.aptxmax;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AptxUserService extends Binder {
    private static final String DESCRIPTOR = "com.nowwwki.aptxmax.AptxUserService";
    private static final int TRANSACTION_APPLY_MAX = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_CHECK_STATUS = IBinder.FIRST_CALL_TRANSACTION + 1;

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
    private static volatile Context serviceContext;

    public AptxUserService() {
    }

    public AptxUserService(Context context) {
        serviceContext = context;
    }

    public void destroy() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code != TRANSACTION_APPLY_MAX && code != TRANSACTION_CHECK_STATUS) {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable exception) {
                reply.writeNoException();
                reply.writeString(stackTrace(exception));
                return true;
            }
        }

        data.enforceInterface(DESCRIPTOR);
        String result;
        try {
            result = code == TRANSACTION_APPLY_MAX ? applyMaxCodec() : checkCurrentCodec();
        } catch (Throwable throwable) {
            result = stackTrace(throwable);
        }
        reply.writeNoException();
        reply.writeString(result);
        return true;
    }

    private static String applyMaxCodec() throws Exception {
        return runWithA2dp(true);
    }

    private static String checkCurrentCodec() throws Exception {
        return runWithA2dp(false);
    }

    private static String runWithA2dp(final boolean apply) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] result = new String[1];
        final Throwable[] error = new Throwable[1];

        exemptHiddenApis();
        initializeBluetoothFramework();

        Context context = getSystemContext();
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IllegalStateException("BluetoothAdapter is null");
        }

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    result[0] = handleA2dp(adapter, (BluetoothA2dp) proxy, apply);
                } catch (Throwable throwable) {
                    error[0] = throwable;
                } finally {
                    try {
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    } catch (Throwable ignored) {
                    }
                    done.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };

        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
            throw new IllegalStateException("getProfileProxy(A2DP) returned false");
        }

        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for Bluetooth A2DP service");
        }
        if (error[0] != null) {
            throw new RuntimeException(error[0]);
        }
        return result[0] == null ? "No result." : result[0];
    }

    private static String handleA2dp(BluetoothAdapter adapter, BluetoothA2dp a2dp, boolean apply)
            throws Exception {
        List<DeviceChoice> choices = collectConnectedDevices(a2dp, adapter);
        if (choices.isEmpty()) {
            throw new IllegalStateException("Подключенные A2DP-наушники не найдены. Сначала подключи наушники.");
        }

        if (!apply) {
            return describeConnectedDevices(choices);
        }

        DeviceChoice target = chooseBestDevice(choices);
        if (target == null || target.bestCodec == null) {
            throw new IllegalStateException("Подключенное устройство не сообщает доступные A2DP-кодеки.");
        }

        BluetoothDevice device = target.device;
        BluetoothCodecStatus before = target.status;
        BluetoothCodecConfig requested = target.bestCodec.config;

        applyGlobalSettings(requested);
        Method setPreference = BluetoothA2dp.class.getDeclaredMethod(
                "setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
        setPreference.setAccessible(true);
        Object setResult = setPreference.invoke(a2dp, device, requested);

        Thread.sleep(1200);
        BluetoothCodecStatus after = getCodecStatus(a2dp, device);

        return "Устройство: " + safeName(device)
                + "\nБыло: " + describe(before)
                + "\nЗапрошено: " + describe(requested)
                + "\nДоступно для выбора: " + target.bestCodec.selectable
                + "\nЯвный Lossless: " + target.bestCodec.explicitLossless
                + "\nsetCodecConfigPreference: " + setResult
                + "\nСтало: " + describe(after);
    }

    private static List<DeviceChoice> collectConnectedDevices(BluetoothA2dp a2dp, BluetoothAdapter adapter)
            throws Exception {
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

    private static BluetoothCodecStatus getCodecStatus(BluetoothA2dp a2dp, BluetoothDevice device)
            throws Exception {
        Method method = BluetoothA2dp.class.getDeclaredMethod("getCodecStatus", BluetoothDevice.class);
        method.setAccessible(true);
        return (BluetoothCodecStatus) method.invoke(a2dp, device);
    }

    private static String describe(BluetoothCodecStatus status) {
        if (status == null || status.getCodecConfig() == null) {
            return "неизвестно";
        }
        return describe(status.getCodecConfig());
    }

    private static String describe(BluetoothCodecConfig config) {
        if (config == null) {
            return "неизвестно";
        }
        return codecName(config)
                + " / " + sampleRate(config.getSampleRate())
                + " / " + bits(config.getBitsPerSample())
                + " / " + channel(config.getChannelMode())
                + codecSpecific(config);
    }

    private static String describeConnectedDevices(List<DeviceChoice> choices) {
        StringBuilder builder = new StringBuilder();
        builder.append("Подключенных A2DP-устройств: ").append(choices.size());
        for (DeviceChoice choice : choices) {
            builder.append("\n\nУстройство: ").append(safeName(choice.device));
            builder.append("\nТекущий кодек: ").append(describe(choice.status));
            builder.append("\nЛучший доступный: ");
            if (choice.bestCodec == null) {
                builder.append("неизвестно");
            } else {
                builder.append(describe(choice.bestCodec.config));
                builder.append("\nДоступно для выбора: ").append(choice.bestCodec.selectable);
                builder.append("\nЯвный Lossless: ").append(choice.bestCodec.explicitLossless);
            }
        }
        return builder.toString();
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
            return "расширенный кодек";
        }
        return "кодек " + codecType;
    }

    private static String sampleRate(int sampleRate) {
        if (sampleRate == BluetoothCodecConfig.SAMPLE_RATE_NONE) {
            return "частота по умолчанию";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_44100, "44.1");
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_48000, "48");
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_88200, "88.2");
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_96000, "96");
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_176400, "176.4");
        known |= appendRate(builder, sampleRate, SAMPLE_RATE_192000, "192");
        if (builder.length() > 0 && (sampleRate & ~known) == 0) {
            return builder.append(" kHz").toString();
        }
        return "частота 0x" + Integer.toHexString(sampleRate);
    }

    private static String bits(int bits) {
        if (bits == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
            return "битность по умолчанию";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendBits(builder, bits, BITS_16, "16");
        known |= appendBits(builder, bits, BITS_24, "24");
        known |= appendBits(builder, bits, BITS_32, "32");
        if (builder.length() > 0 && (bits & ~known) == 0) {
            return builder.append("-bit").toString();
        }
        return "битность 0x" + Integer.toHexString(bits);
    }

    private static String channel(int channel) {
        if (channel == BluetoothCodecConfig.CHANNEL_MODE_NONE) {
            return "каналы по умолчанию";
        }
        StringBuilder builder = new StringBuilder();
        int known = 0;
        known |= appendChannel(builder, channel, MONO, "моно");
        known |= appendChannel(builder, channel, STEREO, "стерео");
        if (builder.length() > 0 && (channel & ~known) == 0) {
            return builder.toString();
        }
        return "каналы 0x" + Integer.toHexString(channel);
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

    private static int appendRate(StringBuilder builder, int mask, int value, String label) {
        if ((mask & value) == 0) {
            return 0;
        }
        appendPart(builder, label);
        return value;
    }

    private static int appendBits(StringBuilder builder, int mask, int value, String label) {
        if ((mask & value) == 0) {
            return 0;
        }
        appendPart(builder, label);
        return value;
    }

    private static int appendChannel(StringBuilder builder, int mask, int value, String label) {
        if ((mask & value) == 0) {
            return 0;
        }
        appendPart(builder, label);
        return value;
    }

    private static void appendPart(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append("/");
        }
        builder.append(value);
    }

    private static void applyGlobalSettings(BluetoothCodecConfig config) throws Exception {
        putGlobal("bluetooth_a2dp_codec", Integer.toString(config.getCodecType()));
        putGlobal("bluetooth_a2dp_codec_sample_rate", Integer.toString(config.getSampleRate()));
        putGlobal("bluetooth_a2dp_codec_bits_per_sample", Integer.toString(config.getBitsPerSample()));
        putGlobal("bluetooth_a2dp_codec_channel_mode", Integer.toString(config.getChannelMode()));

        String priorityKey = priorityKey(config);
        if (priorityKey != null) {
            putGlobal(priorityKey, Integer.toString(PRIORITY_HIGHEST));
        }
    }

    private static String priorityKey(BluetoothCodecConfig config) {
        String text = codecSearchText(config);
        int codecType = config.getCodecType();
        if (codecType == CODEC_APTX_ADAPTIVE || (text.contains("aptx") && text.contains("adaptive"))) {
            return "bluetooth_a2dp_codec_priority_aptx_adaptive";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC || text.contains("ldac")) {
            return "bluetooth_a2dp_codec_priority_ldac";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD || text.contains("aptx hd")) {
            return "bluetooth_a2dp_codec_priority_aptx_hd";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX || text.contains("aptx")) {
            return "bluetooth_a2dp_codec_priority_aptx";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC || text.contains("aac")) {
            return "bluetooth_a2dp_codec_priority_aac";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC || text.contains("sbc")) {
            return "bluetooth_a2dp_codec_priority_sbc";
        }
        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS || text.contains("opus")) {
            return "bluetooth_a2dp_codec_priority_opus";
        }
        return null;
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
        if (serviceContext != null) {
            return serviceContext;
        }
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

    private static void putGlobal(String key, String value) throws Exception {
        Process process = new ProcessBuilder("/system/bin/settings", "put", "global", key, value)
                .redirectErrorStream(true)
                .start();
        drain(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("settings put global " + key + " failed: " + exitCode);
        }
    }

    private static void drain(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[1024];
        while (inputStream.read(buffer) != -1) {
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return stringWriter.toString();
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
