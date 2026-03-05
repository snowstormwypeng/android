package com.example.browsershell;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final String TAG = "BrowserShell";
    private static final long CARD_LEAVE_TIMEOUT_MS = 1500L;

    private WebView webView;
    private final Map<Integer, String> permissionCallbacks = new HashMap<>();
    private int nextPermissionRequestCode = 2000;
    private String pendingActivityJsCallback;

    private NfcAdapter nfcAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String brushCardCallback;
    private String currentCardNo;
    private long lastCardSeenAt = 0L;
    private Tag currentTag;

    private final Runnable cardLeaveChecker = new Runnable() {
        @Override
        public void run() {
            if (currentCardNo != null) {
                long now = System.currentTimeMillis();
                if (now - lastCardSeenAt > CARD_LEAVE_TIMEOUT_MS) {
                    notifyCardEvent("leave", currentCardNo);
                    currentCardNo = null;
                    currentTag = null;
                }
            }
            mainHandler.postDelayed(this, 500L);
        }
    };

    private final ActivityResultLauncher<Intent> activityLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (pendingActivityJsCallback != null) {
                    String payload = result.getResultCode() == RESULT_OK ? "OK" : "CANCEL";
                    callJsCallback(pendingActivityJsCallback, payload);
                    pendingActivityJsCallback = null;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.web_view);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new ExternalBridge(), "external");
        webView.loadUrl("file:///android_asset/Index.html");

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        mainHandler.post(cardLeaveChecker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            int flags = NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                    | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
            nfcAdapter.enableReaderMode(this, this, flags, null);
        }
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        MifareClassic m1 = MifareClassic.get(tag);
        if (m1 == null) {
            return;
        }
        String cardNo = toHex(tag.getId());
        mainHandler.post(() -> {
            lastCardSeenAt = System.currentTimeMillis();
            currentTag = tag;
            if (currentCardNo == null || !currentCardNo.equals(cardNo)) {
                if (currentCardNo != null) {
                    notifyCardEvent("leave", currentCardNo);
                }
                currentCardNo = cardNo;
                notifyCardEvent("enter", currentCardNo);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        String callbackName = permissionCallbacks.remove(requestCode);
        if (callbackName == null) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        callJsCallback(callbackName, granted);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void notifyCardEvent(String event, String cardNo) {
        if (brushCardCallback == null || brushCardCallback.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("event", event);
            data.put("cardNo", cardNo);
            callJsCallback(brushCardCallback, data.toString());
        } catch (Exception e) {
            Log.e(TAG, "notifyCardEvent error", e);
        }
    }

    private String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private void callJsCallback(String callbackName, Object value) {
        if (callbackName == null || callbackName.trim().isEmpty()) {
            return;
        }
        String jsArg;
        if (value instanceof Boolean || value instanceof Number) {
            jsArg = String.valueOf(value);
        } else {
            jsArg = JSONObject.quote(String.valueOf(value));
        }
        String script = "javascript:if(typeof " + callbackName + " === 'function'){" + callbackName + "(" + jsArg + ");}";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private class ExternalBridge {
        @JavascriptInterface
        public void enableDebug(boolean flag, int h) {
            runOnUiThread(() -> {
                WebView.setWebContentsDebuggingEnabled(flag);
                if (flag) {
                    Toast.makeText(MainActivity.this, "Debug已开启，高度: " + h, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public int getVersionCode() {
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return (int) packageInfo.getLongVersionCode();
                }
                return packageInfo.versionCode;
            } catch (Exception e) {
                Log.e(TAG, "getVersionCode error", e);
                return 1;
            }
        }

        @JavascriptInterface
        public String getAppId() {
            return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        @JavascriptInterface
        public void Close() {
            runOnUiThread(MainActivity.this::finishAffinity);
        }

        @JavascriptInterface
        public String GetAppVer() {
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                return packageInfo.versionName;
            } catch (Exception e) {
                Log.e(TAG, "GetAppVer error", e);
                return "1.0.0";
            }
        }

        @JavascriptInterface
        public void CallActivity(String aName, String jsCall) {
            try {
                Class<?> cls = Class.forName(aName);
                Intent intent = new Intent(MainActivity.this, cls);
                pendingActivityJsCallback = jsCall;
                activityLauncher.launch(intent);
            } catch (Exception e) {
                Log.e(TAG, "CallActivity failed: " + aName, e);
                callJsCallback(jsCall, "ERROR: " + e.getClass().getSimpleName());
            }
        }

        @JavascriptInterface
        public void RequestPermission(String pName, String callback) {
            if (ContextCompat.checkSelfPermission(MainActivity.this, pName) == PackageManager.PERMISSION_GRANTED) {
                callJsCallback(callback, true);
                return;
            }
            int requestCode = nextPermissionRequestCode++;
            permissionCallbacks.put(requestCode, callback);
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{pName}, requestCode);
        }

        @JavascriptInterface
        public void ScanQrcode(String callJs) {
            Toast.makeText(MainActivity.this, "当前示例未集成扫码模块", Toast.LENGTH_SHORT).show();
            callJsCallback(callJs, "NOT_IMPLEMENTED");
        }

        @JavascriptInterface
        public void UpdateVerify() {
            Toast.makeText(MainActivity.this, "UpdateVerify 已触发", Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public void reLoadHome() {
            runOnUiThread(() -> webView.loadUrl("file:///android_asset/Index.html"));
        }

        @JavascriptInterface
        public void setBrushCardEvent(String call) {
            brushCardCallback = call;
            if (currentCardNo != null) {
                notifyCardEvent("enter", currentCardNo);
            }
        }

        @JavascriptInterface
        public String readCardBlock(int blockNo) {
            Tag tag = currentTag;
            if (tag == null) {
                return "";
            }
            MifareClassic m1 = MifareClassic.get(tag);
            if (m1 == null) {
                return "";
            }
            try {
                m1.connect();
                int sector = blockNo / 4;
                boolean auth = m1.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT)
                        || m1.authenticateSectorWithKeyB(sector, MifareClassic.KEY_DEFAULT);
                if (!auth) {
                    return "";
                }
                byte[] data = m1.readBlock(blockNo);
                return Base64.encodeToString(data, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "readCardBlock error", e);
                return "";
            } finally {
                try {
                    m1.close();
                } catch (Exception ignored) {
                }
            }
        }

        @JavascriptInterface
        public String readCardSector(int sectorNo) {
            Tag tag = currentTag;
            if (tag == null) {
                return "";
            }
            MifareClassic m1 = MifareClassic.get(tag);
            if (m1 == null) {
                return "";
            }
            try {
                m1.connect();
                boolean auth = m1.authenticateSectorWithKeyA(sectorNo, MifareClassic.KEY_DEFAULT)
                        || m1.authenticateSectorWithKeyB(sectorNo, MifareClassic.KEY_DEFAULT);
                if (!auth) {
                    return "";
                }
                int blockCount = m1.getBlockCountInSector(sectorNo);
                int blockIndex = m1.sectorToBlock(sectorNo);
                byte[] merged = new byte[blockCount * MifareClassic.BLOCK_SIZE];
                for (int i = 0; i < blockCount; i++) {
                    byte[] block = m1.readBlock(blockIndex + i);
                    System.arraycopy(block, 0, merged, i * MifareClassic.BLOCK_SIZE, MifareClassic.BLOCK_SIZE);
                }
                return Base64.encodeToString(merged, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "readCardSector error", e);
                return "";
            } finally {
                try {
                    m1.close();
                } catch (Exception ignored) {
                }
            }
        }

        @JavascriptInterface
        public String ReadCardSector(int sectorNo) {
            return readCardSector(sectorNo);
        }

        @JavascriptInterface
        public String checkCardCert() {
            if (currentCardNo == null) {
                return "{\"code\":-1,\"msg\":\"未检测到M1卡\"}";
            }
            return "{\"code\":0,\"data\":{\"cardNo\":\"" + currentCardNo + "\"},\"msg\":\"证书验证通过\"}";
        }
    }
}
