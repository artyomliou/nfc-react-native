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
import android.nfc.TagLostException;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.util.Log;

import java.lang.Exception;
import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Queue;
import java.util.LinkedList;
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

    private NfcAdapter mNfcAdapter;

    private volatile int tagId;
    private volatile MifareClassic tag;

    private volatile String authKey = "FFFFFFFFFFFF";
    private volatile String authType = "B";
    private volatile ArrayList<Boolean> authStatuses;
    private volatile Queue<String> operations;
    private volatile Queue<ReadableMap> parameters;
    private volatile Queue<Promise> promises;

    public NfcReactNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        tag = null;

        operations = new LinkedList<String>();
        parameters = new LinkedList<ReadableMap>();
        promises = new LinkedList<Promise>();

        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(); 
        exec.scheduleAtFixedRate(new OperationComsumerThread(), 0, 700, TimeUnit.MILLISECONDS); 
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
        Log.d("ReactNative", "handleIntent");
        tag = MifareClassic.get( (Tag)intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
        tagId = ByteBuffer.wrap(tag.getTag().getId()).getInt();

        // reset variables for further operation
        int sectorCount = tag.getSectorCount();
        resetTagInfos(sectorCount);
        clearQueue();

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

    private class OperationComsumerThread implements Runnable {
        public void run() {
            if (tag == null) {
                return;
            } else if (operations.size() == 0) {
                return;
            }
            if (tag.isConnected() == false) {
                try {
                    tag.connect();
                } catch (IOException e) {
                    Log.d("ReactNative", e.getMessage());
                    clearQueue(e.getMessage());
                    return;
                }
            }

            boolean retrying = false;

            while (operations.size() > 0) {

                String op = operations.element();
                ReadableMap param = parameters.element();
                Promise promise = promises.element();

                int sectorIndex = param.getInt("sector");
                int blockIndex = param.getInt("block");

                if (retrying) {
                    Log.d("ReactNative", "retrying...");
                } else {
                    Log.d("ReactNative", op);
                }

                try {
                    switch (op) {
                        case "read":
                            doRead(sectorIndex, blockIndex, promise);
                            break;
                        case "writeByte":
                            int byteIndex = param.getInt("byte");
                            String data = param.getString("data");
                            doWrite(sectorIndex, blockIndex, byteIndex, data, promise);
                            break;
                        }

                    operations.remove();
                    parameters.remove();
                    promises.remove();
                }
                catch (TagLostException e) {
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e)); 
                    promises.remove();
                    clearQueue(e.getMessage());
                    break;
                    // queue is cleared, no further operation.
                    // loop stop here
                }
                catch (IOException e) {
                    Log.d("ReactNative", e.getMessage());
                    if (retrying) {
                        retrying = false;

                        promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e)); 
                        promises.remove();
                        clearQueue(e.getMessage());
                        break;
                        // queue is cleared, no further operation.
                        // loop stop here
                    } else {
                        authStatuses.set(sectorIndex, false);
                        // current operation will be processed one more time,
                        // with full autnentication procedure
                        // loop will go on
                    }
                }
                catch (Exception e) {
                    Log.d("ReactNative", e.getMessage());
                    promise.reject(E_LAYOUT_ERROR, Log.getStackTraceString(e));
                    promises.remove();
                    clearQueue(e.getMessage());
                    // loop stop here, for any unknown exception
                }
            }
            
            if (tag.isConnected()) {
                try {
                    tag.close();
                } catch (IOException e) {
                    WritableMap error = Arguments.createMap(); 
                    error.putString("error", e.toString()); 
 
                    reactContext 
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class) 
                        .emit("onTagError", error);
                }
            }
        }

        private void auth(int sectorIndex) throws Exception {
            if (authStatuses.get(sectorIndex) == true) {
                return;
            }
            boolean passed = false;
            byte[][] arrayKeys = new byte[4][6];
            arrayKeys[0] = MifareClassic.KEY_DEFAULT;
            arrayKeys[1] = MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY;
            arrayKeys[2] = MifareClassic.KEY_NFC_FORUM;
            arrayKeys[3] = hexStringToByteArray(authKey);

            String[] arrayTypes = new String[4];
            arrayTypes[0] = "A";
            arrayTypes[1] = "A";
            arrayTypes[2] = "A";
            arrayTypes[3] = authType;

            for (int i = 0; i < arrayKeys.length; i++) {
                if ("A".equals(arrayTypes[i])) {
                    passed = tag.authenticateSectorWithKeyA(sectorIndex, arrayKeys[i]);
                } else {
                    passed = tag.authenticateSectorWithKeyB(sectorIndex, arrayKeys[i]);
                }
                
                if (passed) {
                    Log.d("ReactNative", "Sector " + String.valueOf(sectorIndex) + ", " + byteArrayToHexString(arrayKeys[i]));
                    break;
                }
            }
            authStatuses.set(sectorIndex, passed);
    
            if (passed == false) {
                throw new IOException("Authentication failed: sector" + String.valueOf(sectorIndex) + ", type" + authType + ", key=" + authKey);
            }
        }

        private void doRead(int sectorIndex, int blockIndex, Promise promise) throws TagLostException, Exception {
            auth(sectorIndex);

            blockIndex = getRealBlockIndex(sectorIndex, blockIndex);
            Log.d("ReactNative", String.valueOf(sectorIndex) + "," + String.valueOf(blockIndex));
                
            byte[] blockBytes = tag.readBlock(blockIndex);
            String blockString = byteArrayToHexString(blockBytes);

            WritableMap map = Arguments.createMap();
            map.putString("payload", blockString);
            promise.resolve(map);
        }

        private void doWrite(int sectorIndex, int blockIndex, int byteIndex, String data, Promise promise) throws TagLostException, Exception {
            auth(sectorIndex);

            blockIndex = getRealBlockIndex(sectorIndex, blockIndex);
            Log.d("ReactNative", String.valueOf(sectorIndex) + "," + String.valueOf(blockIndex));
            
            byte[] blockBytes = tag.readBlock(blockIndex);
            if (byteIndex > blockBytes.length - 1) {
                throw new Exception("Out of bound: invalid byte index to wrtie");
            } else {
                blockBytes = overwriteBytesWithString(blockBytes, byteIndex, data);
                tag.writeBlock(blockIndex, blockBytes);
            }

            WritableMap map = Arguments.createMap();
            map.putString("payload", byteArrayToHexString(blockBytes));
            promise.resolve(map);
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
                throw new Exception("Out of bound: sector " + String.valueOf(sectorIndex) + ", block " + String.valueOf(blockIndex));
            }
        }

        private byte[] overwriteBytesWithString(byte[] blockBytes, int byteIndex, String data) throws UnsupportedEncodingException {
            byte[] bytes = data.getBytes("UTF-8");
            for(byte b:bytes) {
                blockBytes[byteIndex] = b;
                byteIndex++;
            }
            return blockBytes;
        }
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
    public void setKey(String newKey, String newType) {
        if (newKey == null || newType == null) {
            return;
        }
        authKey = newKey;
        authType = newType;
    }

    @ReactMethod
    public void read(ReadableMap map, Promise promise) {
        operations.add("read");
        parameters.add(map);
        promises.add(promise);
    }

    @ReactMethod
    public void writeByte(ReadableMap map, Promise promise) {
        operations.add("writeByte");
        parameters.add(map);
        promises.add(promise);
    }

    private void resetTagInfos(int sectorCount) {
        Log.d("ReactNative", "resetTagInfos");
        authStatuses = new ArrayList<Boolean>(Collections.nCopies(sectorCount, false));
    }

    private void clearQueue() {
        Log.d("ReactNative", "clearQueue");
        operations.clear();
        parameters.clear();

        while (promises.size() > 0) {
            Promise promise = promises.remove();
            promise.reject(E_LAYOUT_ERROR, "Clear queue.");
        }
    }

    private void clearQueue(String message) {
        Log.d("ReactNative", "clearQueue");
        operations.clear();
        parameters.clear();

        while (promises.size() > 0) {
            Promise promise = promises.remove();
            promise.reject(E_LAYOUT_ERROR, message);
        }
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
