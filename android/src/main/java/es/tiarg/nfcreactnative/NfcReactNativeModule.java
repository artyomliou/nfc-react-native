package es.tiarg.nfcreactnative;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.TagLostException;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.util.Log;
import android.os.AsyncTask;

import java.lang.Exception;
import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.ArrayList;



class NfcReactNativeModule extends ReactContextBaseJavaModule implements ActivityEventListener, LifecycleEventListener {
    private ReactApplicationContext reactContext;

    private static final String E_LAYOUT_ERROR = "E_LAYOUT_ERROR";

    private NfcAdapter mNfcAdapter;

    private volatile int tagId;
    private volatile MifareClassic tag;

    private volatile String authKey = "FFFFFFFFFFFF";
    private volatile String authType = "B";
    private volatile ArrayList<Boolean> authStatuses;

    public NfcReactNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        tag = null;

        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);
    }

    @Override
    public void onHostResume() {
        if (mNfcAdapter != null) {
            setupForegroundDispatch(getCurrentActivity(), mNfcAdapter);
        } else {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(reactContext);
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
        Log.d("NfcReactNative", "handleIntent");
        Tag tagFromIntent = (Tag)intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tagFromIntent == null) {
            Log.d("NfcReactNative", "Cannot get tag from intent.");
            return;
        }

        tag = MifareClassic.get(tagFromIntent);
        tagId = ByteBuffer.wrap(tag.getTag().getId()).getInt();

        // reset variables for further operation
        int sectorCount = tag.getSectorCount();
        resetTagInfos(sectorCount);

        // pass info about this tag to JS
        WritableMap map = Arguments.createMap();
        map.putInt("id", tagId);
        map.putInt("size", tag.getSize());
        map.putInt("timeout", tag.getTimeout());
        map.putInt("type", tag.getType());

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
    public void setKey(final String newKey, final String newType) {
        if (newKey == null || newType == null) {
            return;
        }
        authKey = newKey;
        authType = newType;
    }

    @ReactMethod
    public void read(final ReadableMap param, final Promise promise) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connect();
                } catch (IOException e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                    return;
                }
                try {
                    int sectorIndex = param.getInt("sector");
                    int blockIndex = param.getInt("block");

                    blockIndex = getRealBlockIndex(sectorIndex, blockIndex);
                    Log.d("NfcReactNative", String.valueOf(sectorIndex) + "," + String.valueOf(blockIndex));
                    auth(sectorIndex);

                    byte[] blockBytes = tag.readBlock(blockIndex);
                    String blockString = byteArrayToHexString(blockBytes);

                    WritableMap returns = Arguments.createMap();
                    returns.putString("payload", blockString);
                    promise.resolve(returns);

                } catch (IOException e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                } catch (Exception e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                }
            }
        });
    }

    @ReactMethod
    public void writeByte(final ReadableMap param, final Promise promise) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connect();
                } catch (IOException e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                    return;
                }
                try {
                    int sectorIndex = param.getInt("sector");
                    int blockIndex = param.getInt("block");
                    int byteIndex = param.getInt("byte");
                    String data = param.getString("data");

                    blockIndex = getRealBlockIndex(sectorIndex, blockIndex);
                    Log.d("NfcReactNative", String.valueOf(sectorIndex) + "," + String.valueOf(blockIndex));
                    auth(sectorIndex);

                    byte[] blockBytes = tag.readBlock(blockIndex);
                    if (byteIndex > blockBytes.length - 1) {
                        throw new Exception("Out of bound: invalid byte index to wrtie");
                    } else {
                        blockBytes = overwriteBytesWithString(blockBytes, byteIndex, data);
                        tag.writeBlock(blockIndex, blockBytes);
                    }

                    WritableMap returns = Arguments.createMap();
                    returns.putString("payload", byteArrayToHexString(blockBytes));
                    promise.resolve(returns);

                } catch (IOException e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                } catch (Exception e) {
                    Log.d("NfcReactNative", "" + e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                }
            }
        });
    }

    @ReactMethod
    public void close(final Promise promise) {
        try {
            if (tag == null) {
                throw new IOException("Lost tag. Cant close connection.");
            }
            if (tag.isConnected()) {
                tag.close();
            }
            WritableMap returns = Arguments.createMap();
            returns.putBoolean("payload", true);
            promise.resolve(returns);
        } catch (IOException e) {
            Log.d("NfcReactNative", "" + e.getMessage());
            promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
        }
    }

    private void connect() throws IOException {
        if (tag == null) {
            throw new IOException("Lost tag. Cant start connection.");
        }
        if (tag.isConnected() == false) {
            tag.connect();
        }
    }

    private void auth(int sectorIndex) throws Exception {
        if (authStatuses.get(sectorIndex) == true) {
            return;
        }
        boolean passed = false;
        byte[][] arrayKeys = new byte[2][6];
        arrayKeys[0] = hexStringToByteArray(authKey);
        arrayKeys[1] = arrayKeys[0];
        // arrayKeys[2] = MifareClassic.KEY_DEFAULT;
        // arrayKeys[3] = MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY;
        // arrayKeys[4] = MifareClassic.KEY_NFC_FORUM;

        String[] arrayTypes = new String[2];
        arrayTypes[0] = authType;
        arrayTypes[1] = "A".equals(authType) ? "B" : "A";
        // arrayTypes[2] = "A";
        // arrayTypes[3] = "A";
        // arrayTypes[4] = "A";

        for (int i = 0; i < arrayKeys.length; i++) {
            try {
                if ("A".equals(arrayTypes[i])) {
                    passed = tag.authenticateSectorWithKeyA(sectorIndex, arrayKeys[i]);
                } else {
                    passed = tag.authenticateSectorWithKeyB(sectorIndex, arrayKeys[i]);
                }
            } catch (TagLostException e) {
                throw e;
            } catch (IOException e) {
                Log.d("NfcReactNative", "" + e.getMessage());
            }
            
            if (passed) {
                break;
            }
        }
        authStatuses.set(sectorIndex, passed);

        if (passed == false) {
            throw new IOException("Authentication failed: sector" + String.valueOf(sectorIndex) + ", type" + authType + ", key=" + authKey);
        }
    }

    /**
     * When you want to access block 7 in sector 2
     * (which usually means the last block in sector)
     * You'll probably get blockIndex as 7 or 3.
     * 
     * When you get blockIndex as 3, 
     * you have to calculate the real blockIndex which Android API needs.
     * 
     * This function is designed to fix this.
     */
    private int getRealBlockIndex(int sectorIndex, int blockIndex) throws Exception {
        int firstBlockInSector = tag.sectorToBlock(sectorIndex);
        int blockCounts = tag.getBlockCountInSector(sectorIndex);
        int lastBlockInSector = firstBlockInSector + blockCounts - 1;

        if (firstBlockInSector <= blockIndex && blockIndex <= lastBlockInSector) {
            // blockCounts = 4, sector = 3, block = 9
            return blockIndex;
        } else if (0 <= blockIndex && blockIndex <= blockCounts - 1) {
            // blockCounts = 4, sector = 3, block = 0
            return firstBlockInSector + blockIndex - 1;
        } else {
            throw new Exception(
                "Out of bound: sector " + String.valueOf(sectorIndex) + ", block " + String.valueOf(blockIndex)
            );
        }
    }

    private byte[] overwriteBytesWithString(byte[] blockBytes, int byteIndex, String data)
            throws UnsupportedEncodingException {
        byte[] bytes = data.getBytes("UTF-8");
        for (byte b : bytes) {
            blockBytes[byteIndex] = b;
            byteIndex++;
        }
        return blockBytes;
    }

    private void resetTagInfos(int sectorCount) {
        Log.d("NfcReactNative", "resetTagInfos");
        authStatuses = new ArrayList<Boolean>(Collections.nCopies(sectorCount, false));
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
