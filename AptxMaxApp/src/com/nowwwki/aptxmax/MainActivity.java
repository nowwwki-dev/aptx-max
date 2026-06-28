package com.nowwwki.aptxmax;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 991;
    private static final String DESCRIPTOR = "com.nowwwki.aptxmax.AptxUserService";
    private static final int TRANSACTION_APPLY_MAX = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_CHECK_STATUS = IBinder.FIRST_CALL_TRANSACTION + 1;

    private TextView stateView;
    private TextView logView;
    private Button actionButton;
    private Button checkButton;

    private IBinder userService;
    private Shizuku.UserServiceArgs serviceArgs;
    private int pendingTransaction;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateState();
                        }
                    });
                }
            };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_SHIZUKU) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateState();
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                appendLog("Разрешение Shizuku выдано.");
                            } else {
                                appendLog("Разрешение Shizuku отклонено.");
                            }
                        }
                    });
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            userService = service;
            final int transaction = pendingTransaction;
            pendingTransaction = 0;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setBusy(false);
                    appendLog("Shell-сервис подключен.");
                    if (transaction != 0) {
                        runRemote(transaction);
                    }
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            userService = null;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    appendLog("Shell-сервис отключен.");
                    updateState();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        updateState();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(246, 247, 249));

        TextView title = new TextView(this);
        title.setText("aptX Max");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setGravity(Gravity.START);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        stateView = new TextView(this);
        stateView.setTextSize(15);
        stateView.setTextColor(Color.rgb(55, 65, 81));
        stateView.setPadding(0, dp(10), 0, dp(16));
        root.addView(stateView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        actionButton = new Button(this);
        actionButton.setText("Включить лучший кодек");
        actionButton.setAllCaps(false);
        actionButton.setTextSize(17);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!ensureReady(TRANSACTION_APPLY_MAX)) {
                    return;
                }
                runRemote(TRANSACTION_APPLY_MAX);
            }
        });
        root.addView(actionButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        checkButton = new Button(this);
        checkButton.setText("Проверить кодек");
        checkButton.setAllCaps(false);
        checkButton.setTextSize(16);
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!ensureReady(TRANSACTION_CHECK_STATUS)) {
                    return;
                }
                runRemote(TRANSACTION_CHECK_STATUS);
            }
        });
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        checkParams.topMargin = dp(10);
        root.addView(checkButton, checkParams);

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextColor(Color.rgb(31, 41, 55));
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setText("Подключи любые A2DP-наушники, запусти Shizuku и нажми \"Включить лучший кодек\".\n");
        logView.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);
        scrollView.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.topMargin = dp(16);
        root.addView(scrollView, logParams);

        setContentView(root);
    }

    private boolean ensureReady(int transaction) {
        if (!isShizukuRunning()) {
            appendLog("Shizuku не запущен. Сначала запусти Shizuku.");
            openShizukuManager();
            updateState();
            return false;
        }
        if (!hasShizukuPermission()) {
            appendLog("Запрашиваю разрешение Shizuku...");
            Shizuku.requestPermission(REQUEST_SHIZUKU);
            return false;
        }
        if (userService == null || !userService.isBinderAlive()) {
            bindUserService(transaction);
            return false;
        }
        return true;
    }

    private void bindUserService(int transaction) {
        pendingTransaction = transaction;
        setBusy(true);
        appendLog("Подключаю shell-сервис...");
        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), AptxUserService.class.getName()))
                .daemon(false)
                .debuggable(false)
                .processNameSuffix("aptx")
                .tag("aptx-max")
                .version(6);
        Shizuku.bindUserService(serviceArgs, connection);
    }

    private void runRemote(final int transaction) {
        if (userService == null || !userService.isBinderAlive()) {
            bindUserService(transaction);
            return;
        }
        setBusy(true);
        appendLog(transaction == TRANSACTION_APPLY_MAX
                ? "Включаю лучший доступный кодек..."
                : "Читаю текущий кодек...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result;
                try {
                    result = transact(transaction);
                } catch (Throwable throwable) {
                    final String message = throwable.toString();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            appendLog(message);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        appendLog(result);
                        updateState();
                    }
                });
            }
        }, "aptx-max-call").start();
    }

    private String transact(int transaction) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            userService.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void updateState() {
        if (!isShizukuRunning()) {
            stateView.setText("Shizuku: не запущен");
            actionButton.setText("Запустить Shizuku");
            return;
        }
        if (!hasShizukuPermission()) {
            stateView.setText("Shizuku: запущен, нужно разрешение");
            actionButton.setText("Дать разрешение");
            return;
        }
        stateView.setText(userService != null && userService.isBinderAlive()
                ? "Shizuku: готово, shell-сервис подключен"
                : "Shizuku: готово");
        actionButton.setText("Включить лучший кодек");
    }

    private boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openShizukuManager() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent == null) {
            appendLog("Приложение Shizuku не установлено.");
            return;
        }
        appendLog("Открываю Shizuku...");
        startActivity(intent);
    }

    private void setBusy(boolean busy) {
        actionButton.setEnabled(!busy);
        checkButton.setEnabled(!busy);
    }

    private void appendLog(String message) {
        logView.append("\n" + message + "\n");
        final ScrollView parent = (ScrollView) logView.getParent();
        parent.post(new Runnable() {
            @Override
            public void run() {
                parent.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
