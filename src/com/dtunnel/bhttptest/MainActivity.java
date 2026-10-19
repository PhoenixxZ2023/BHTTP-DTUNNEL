package com.dtunnel.bhttptest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION = 4102;
    private EditText host;
    private EditText port;
    private EditText user;
    private EditText password;
    private EditText dns;
    private EditText uploadSlots;
    private EditText downloadSlots;
    private TextView status;
    private TextView log;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(DtunnelVpnService.EXTRA_MESSAGE);
            String state = intent.getStringExtra(DtunnelVpnService.EXTRA_STATE);
            if (message != null) appendLog(message);
            if (state != null) updateStatus(state);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        restoreSettings();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4103);
        }
        appendLog("Bilola Tunnel 1.0.20 — núcleo funcional 1.0.11");
        appendLog("VPN: BHP1/BHTTP -> SSH/PAM -> SOCKS5 -> tun2socks");
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        TextView title = new TextView(this);
        title.setText("Bilola Tunnel");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(13, 71, 161));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, matchWrap());

        host = input("Servidor BHTTP", InputType.TYPE_CLASS_TEXT);
        port = input("Porta", InputType.TYPE_CLASS_NUMBER);
        user = input("Usuário PAM", InputType.TYPE_CLASS_TEXT);
        password = input("Senha", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dns = input("DNS personalizado (IPv4)", InputType.TYPE_CLASS_PHONE);
        root.addView(host, matchWrap());
        root.addView(port, matchWrap());
        root.addView(user, matchWrap());
        root.addView(password, matchWrap());
        root.addView(dns, matchWrap());

        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.HORIZONTAL);
        uploadSlots = input("Slots upload (1–16)", InputType.TYPE_CLASS_NUMBER);
        downloadSlots = input("Slots download (1–32)", InputType.TYPE_CLASS_NUMBER);
        slots.addView(uploadSlots, weighted());
        slots.addView(downloadSlots, weighted());
        root.addView(slots, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        Button connect = new Button(this);
        connect.setText("CONECTAR VPN");
        connect.setOnClickListener(v -> requestVpn());
        Button stop = new Button(this);
        stop.setText("PARAR");
        stop.setOnClickListener(v -> stopVpn());
        actions.addView(connect, weighted());
        actions.addView(stop, weighted());
        root.addView(actions, matchWrap());

        Button copyLogs = new Button(this);
        copyLogs.setText("COPIAR LOGS");
        copyLogs.setOnClickListener(v -> copyLogs());
        root.addView(copyLogs, matchWrap());

        status = new TextView(this);
        status.setText("● Desconectado");
        status.setTextSize(17);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status, matchWrap());

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(Color.rgb(30, 30, 30));
        log.setBackgroundColor(Color.WHITE);
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        log.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(log, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private EditText input(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        return edit;
    }

    private void requestVpn() {
        if (!validate()) return;
        saveSettings();
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION);
        else startVpn();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startVpn();
            else appendLog("Permissão VPN recusada pelo usuário");
        }
    }

    private void startVpn() {
        Intent service = new Intent(this, DtunnelVpnService.class)
                .setAction(DtunnelVpnService.ACTION_START)
                .putExtra(DtunnelVpnService.EXTRA_HOST, host.getText().toString().trim())
                .putExtra(DtunnelVpnService.EXTRA_PORT,
                        Integer.parseInt(port.getText().toString().trim()))
                .putExtra(DtunnelVpnService.EXTRA_USER, user.getText().toString().trim())
                .putExtra(DtunnelVpnService.EXTRA_PASSWORD, password.getText().toString())
                .putExtra(DtunnelVpnService.EXTRA_DNS, dns.getText().toString().trim())
                .putExtra(DtunnelVpnService.EXTRA_UPLOAD_SLOTS,
                        Integer.parseInt(uploadSlots.getText().toString().trim()))
                .putExtra(DtunnelVpnService.EXTRA_DOWNLOAD_SLOTS,
                        Integer.parseInt(downloadSlots.getText().toString().trim()));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        updateStatus("connecting");
    }

    private void stopVpn() {
        Intent service = new Intent(this, DtunnelVpnService.class)
                .setAction(DtunnelVpnService.ACTION_STOP);
        startService(service);
        updateStatus("stopping");
    }

    private void copyLogs() {
        String serviceLogs = DtunnelVpnService.getLogSnapshot();
        String text = serviceLogs.isEmpty()
                ? log.getText().toString()
                : "Bilola Tunnel 1.0.20 — núcleo funcional 1.0.11\n"
                + "VPN: BHP1/BHTTP -> SSH/PAM -> SOCKS5 -> tun2socks\n"
                + serviceLogs;
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Bilola Tunnel logs", text));
        Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show();
    }

    private boolean validate() {
        String hostText = host.getText().toString().trim();
        String portText = port.getText().toString().trim();
        if (hostText.isEmpty() || portText.isEmpty()
                || user.getText().toString().trim().isEmpty()
                || password.getText().toString().isEmpty()
                || dns.getText().toString().trim().isEmpty()
                || uploadSlots.getText().toString().trim().isEmpty()
                || downloadSlots.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Preencha host, porta, usuário, senha, DNS e slots",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            int parsed = Integer.parseInt(portText);
            if (parsed < 1 || parsed > 65535) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            Toast.makeText(this, "Porta inválida", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!isIpv4(dns.getText().toString().trim())) {
            Toast.makeText(this, "DNS deve ser um endereço IPv4 válido",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (!validSlot(uploadSlots, 1, 16, "Slots de upload")
                || !validSlot(downloadSlots, 1, 32, "Slots de download")) {
            return false;
        }
        return true;
    }

    private boolean validSlot(EditText field, int minimum, int maximum, String name) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value >= minimum && value <= maximum) return true;
        } catch (NumberFormatException ignored) {
        }
        Toast.makeText(this, name + " deve estar entre " + minimum + " e "
                + maximum, Toast.LENGTH_LONG).show();
        return false;
    }

    private void updateStatus(String state) {
        if ("connected".equals(state)) {
            status.setText("● VPN conectada"); status.setTextColor(Color.rgb(0, 130, 65));
        } else if ("authenticating".equals(state)) {
            status.setText("● Autenticando PAM..."); status.setTextColor(Color.rgb(239, 108, 0));
        } else if ("connecting".equals(state)) {
            status.setText("● Conectando..."); status.setTextColor(Color.rgb(21, 101, 192));
        } else if ("reconnecting".equals(state)) {
            status.setText("● Reconectando..."); status.setTextColor(Color.rgb(198, 40, 40));
        } else if ("stopping".equals(state)) {
            status.setText("● Parando VPN..."); status.setTextColor(Color.rgb(198, 40, 40));
        } else {
            status.setText("● Desconectado"); status.setTextColor(Color.DKGRAY);
        }
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.append(time + "  " + message + "\n");
        if (log.length() > 100000) log.setText(log.getText().subSequence(20000, log.length()));
        final android.view.View parent = (android.view.View) log.getParent();
        parent.post(() -> parent.scrollTo(0, log.getBottom()));
    }

    private void saveSettings() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("host", host.getText().toString().trim())
                .putString("port", port.getText().toString().trim())
                .putString("user", user.getText().toString().trim())
                .putString("password", password.getText().toString())
                .putString("dns", dns.getText().toString().trim())
                .putString("upload_slots", uploadSlots.getText().toString().trim())
                .putString("download_slots", downloadSlots.getText().toString().trim())
                .apply();
    }

    private void restoreSettings() {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        host.setText(preferences.getString("host", "137.131.163.233"));
        port.setText(preferences.getString("port", "80"));
        user.setText(preferences.getString("user", ""));
        password.setText(preferences.getString("password", ""));
        dns.setText(preferences.getString("dns", "1.1.1.1"));
        uploadSlots.setText(preferences.getString("upload_slots", "16"));
        downloadSlots.setText(preferences.getString("download_slots", "32"));
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DtunnelVpnService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
