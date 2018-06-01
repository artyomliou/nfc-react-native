package es.tiarg.nfcreactnative;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableNativeArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.util.Log;

import java.lang.Exception;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static android.R.attr.action;
import static android.R.attr.data;
import static android.R.attr.defaultValue;
import static android.R.attr.id;
import static android.R.attr.tag;
import static android.R.attr.x;
import static android.content.ContentValues.TAG;
import static android.view.View.X;
import static com.facebook.common.util.Hex.hexStringToByteArray;



class NfcReactNativeModule extends ReactContextBaseJavaModule implements ActivityEventListener, LifecycleEventListener {
    private ReactApplicationContext reactContext;

    private static final String E_LAYOUT_ERROR = "E_LAYOUT_ERROR";

    private ArrayList<String> keys;
    private ArrayList<String> types;
    private ArrayList<Boolean> authStatuses;

    private boolean readOperation;
    private boolean writeOperation;
    private int tagId;

    private ReadableArray sectores;
    private NfcAdapter mNfcAdapter;
    private MifareClassic tag;

    public NfcReactNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.tag = null;

        this.reactContext.addActivityEventListener(this);
        this.reactContext.addLifecycleEventListener(this);

        this.readOperation = false;
        this.writeOperation = false;
    }

    @Override
    public void onHostResume() {
        if (mNfcAdapter != null) {
            setupForegroundDispatch(getCurrentActivity(), mNfcAdapter);
        } else {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
        }
    }

    @Override
    public void onHostPause() {
        if (mNfcAdapter != null)
            stopForegroundDispatch(getCurrentActivity(), mNfcAdapter);
    }

    @Override
    public void onHostDestroy() {
        // Activity `onDestroy`
    }

    @Override
    public void onActivityResult(
            final Activity activity,
            final int requestCode,
            final int resultCode,
            final Intent intent) {
    }

    @Override
    public void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        this.tag = MifareClassic.get( (Tag)intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
        this.tagId = ByteBuffer.wrap(this.tag.getTag().getId()).getInt();

        int length = this.tag.getSectorCount();
        this.authStatuses = new ArrayList<Boolean>(length);
        this.keys = new ArrayList<String>(length);
        this.types = new ArrayList<String>(length);

        WritableMap map = Arguments.createMap();
        map.putInt("id", this.tagId);
        map.putInt("size", this.tag.getSize());
        map.putInt("timeout", this.tag.getTimeout());
        map.putInt("type", this.tag.getType());

        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("onTagDetected", map);
    }

    public static void setupForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        final Intent intent = new Intent(activity.getApplicationContext(), activity.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        final PendingIntent pendingIntent = PendingIntent.getActivity(activity.getApplicationContext(), 0, intent, 0);
        adapter.enableForegroundDispatch(activity, pendingIntent, null, null);
    }

    public static void stopForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        adapter.disableForegroundDispatch(activity);
    }

    /**
     * @return the name of this module. This will be the name used to {@code require()} this module
     * from javascript.
     */
    @Override
    public String getName() {
        return "NfcReactNative";
    }

    @ReactMethod
    public void setKeys(ReadableArray keys, ReadableArray types) {
        this.keys = new ArrayList<String>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            this.keys.set(i, keys.getString(i));
        }
        this.types = new ArrayList<String>(types.size());
        for (int i = 0; i < types.size(); i++) {
            this.types.set(i, types.getString(i));
        }
    }

    @ReactMethod
    public void connect(Promise promise) {
        WritableMap map = Arguments.createMap();
        try {
            if (tag == null) {
                throw new Exception("Didnt detected any tag");
            }
            if (tag.isConnected() == true) {
                throw new Exception("Connected with " + String.valueOf(this.tagId));
            }

            tag.connect();

            map.putBoolean("status", true);
            promise.resolve(map);

        } catch (Exception e) {
            promise.reject(E_LAYOUT_ERROR, e);
        }
    }

    @ReactMethod
    public void close(Promise promise) {
        WritableMap map = Arguments.createMap();
        try {
            if (tag == null) {
                throw new Exception("Didnt detected any tag");
            }
            if (tag.isConnected() == false) {
                throw new Exception("Not connected");
            }
            
            tag.close();

            map.putBoolean("status", true);
            promise.resolve(map);
            
        } catch (Exception e) {
            promise.reject(E_LAYOUT_ERROR, e);
        }
    }

    @ReactMethod
    public void read(int sectorIndex, int blockIndex, Promise promise) {
        WritableMap map = Arguments.createMap();
        try {
            auth(sectorIndex);
            byte[] blockBytes = tag.readBlock(4 * sectorIndex + blockIndex);
            String blockString = byteArrayToHexString(blockBytes);

            map.putBoolean("status", true);
            map.putString("payload", blockString);
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject(E_LAYOUT_ERROR, e);
        }
    }

    @ReactMethod
    public void write(int sectorIndex, int blockIndex, ReadableArray values, Promise promise) {
        WritableMap map = Arguments.createMap();
        try {
            auth(sectorIndex);
            int[] valuesArray = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                valuesArray[i] = values.getInt(i);
            }
            byte[] valueBytes = arrayIntsToArrayBytes(valuesArray);

            tag.writeBlock(4 * sectorIndex + blockIndex, valueBytes);

            map.putBoolean("status", true);
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject(E_LAYOUT_ERROR, e);
        }
    }

    @ReactMethod
    public void readTag(ReadableArray sectores) {
        this.sectores = sectores;
        this.readOperation = true;
        this.writeOperation = false;
    }

    @ReactMethod
    public void writeTag(ReadableArray sectores) {
        this.sectores = sectores;
        this.writeOperation = true;
        this.readOperation = false;
    }

    private void auth(int sectorIndex) {
        if (authStatuses.get(sectorIndex) == true) {
            return;
        }
        byte[] keyBytes = keys.get(sectorIndex).getBytes();
        boolean passed;
        if (types.get(sectorIndex).equals("A")) {
            passed = tag.authenticateSectorWithKeyA(sectorIndex, keyBytes);
        } else {
            passed = tag.authenticateSectorWithKeyB(sectorIndex, keyBytes);
        }
        authStatuses.set(sectorIndex, passed);
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private static String byteArrayToHexString(byte[] b) throws Exception {
      String result = "";
      for (int i=0; i < b.length; i++) {
        result +=
              Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 );
      }
      return result;
    }

    private static byte[] arrayIntsToArrayBytes(int[] listaInts) {
        
        ByteBuffer bytebuffer = ByteBuffer.allocate(16);
        for (int i = 0; i < 16; i++) {
            byte high = (byte)((byte)listaInts[i*2] & 0xf0 >> 4);
            byte low =  (byte)((byte)listaInts[i*2+1] & 0x0f);
            bytebuffer.put((byte)(high << 4 | low));
        }
        return bytebuffer.array();
    }

    private static int[] arrayBytesToArrayInts(byte[] listaBytes) {
        
        IntBuffer arraybuffer = IntBuffer.allocate(32);
        for(byte b : listaBytes) {
            int high = (b & 0xf0) >> 4;
            int low = b & 0x0f;
            arraybuffer.put(high);
            arraybuffer.put(low);
        }
        
        return arraybuffer.array();
    }

    static String bin2hex(byte[] data) {
        return String.format("%0" + (data.length * 2) + "X", new BigInteger(1,data));
    }
    
}
