package id.kakzaki.blue_thermal_printer;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Build;
import android.util.Log;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class BlueThermalPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {

    private static final String TAG = "BThermalPrinterPlugin";
    private static final String NAMESPACE = "blue_thermal_printer";
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static ConnectedThread THREAD = null;
    private BluetoothAdapter mBluetoothAdapter;

    private Result pendingResult;

    private EventSink readSink;
    private EventSink statusSink;

    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private final Object initializationLock = new Object();
    private Context context;
    private MethodChannel channel;

    private EventChannel stateChannel;
    private BluetoothManager mBluetoothManager;

    private Activity activity;

    public BlueThermalPrinterPlugin() {
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        setup(
                pluginBinding.getBinaryMessenger(),
                (Application) pluginBinding.getApplicationContext(),
                activityBinding.getActivity(),
                activityBinding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        detach();
    }

    private void setup(
            final BinaryMessenger messenger,
            final Application application,
            final Activity activity,
            final ActivityPluginBinding activityBinding) {
        synchronized (initializationLock) {
            Log.i(TAG, "setup");
            this.activity = activity;
            this.context = application;
            channel = new MethodChannel(messenger, NAMESPACE + "/methods");
            channel.setMethodCallHandler(this);
            stateChannel = new EventChannel(messenger, NAMESPACE + "/state");
            stateChannel.setStreamHandler(stateStreamHandler);
            EventChannel readChannel = new EventChannel(messenger, NAMESPACE + "/read");
            readChannel.setStreamHandler(readResultsHandler);
            mBluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            activityBinding.addRequestPermissionsResultListener(this);
        }
    }


    private void detach() {
        Log.i(TAG, "detach");
        context = null;
        activityBinding.removeRequestPermissionsResultListener(this);
        activityBinding = null;
        channel.setMethodCallHandler(null);
        channel = null;
        stateChannel.setStreamHandler(null);
        stateChannel = null;
        mBluetoothAdapter = null;
        mBluetoothManager = null;
    }

    // MethodChannel.Result wrapper that responds on the platform thread.
    private static class MethodResultWrapper implements Result {
        private final Result methodResult;
        private final Handler handler;

        MethodResultWrapper(Result result) {
            methodResult = result;
            handler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void success(final Object result) {
            handler.post(() -> methodResult.success(result));
        }

        @Override
        public void error(@NonNull final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(() -> methodResult.error(errorCode, errorMessage, errorDetails));
        }

        @Override
        public void notImplemented() {
            handler.post(methodResult::notImplemented);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result rawResult) {
        Result result = new MethodResultWrapper(rawResult);

        if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
            return;
        }

        final Map<String, Object> arguments = call.arguments();
        switch (call.method) {

            case "state":
                state(result);
                break;

            case "isAvailable":
                result.success(mBluetoothAdapter != null);
                break;

            case "isOn":
                try {
                    result.success(mBluetoothAdapter.isEnabled());
                } catch (Exception ex) {
                    result.error("Error", ex.getMessage(), exceptionToString(ex));
                }
                break;

            case "isConnected":
                result.success(THREAD != null);
                break;

            case "isDeviceConnected":
                if (arguments.containsKey("address")) {
                    String address = (String) arguments.get("address");
                    isDeviceConnected(result, address);
                } else {
                    result.error("invalid_argument", "argument 'address' not found", null);
                }
                break;

            case "openSettings":
                ContextCompat.startActivity(context, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                        null);
                result.success(true);
                break;

            case "getBondedDevices":
                try {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        if (ContextCompat.checkSelfPermission(activity,
                                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(activity,
                                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(activity,
                                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                            ActivityCompat.requestPermissions(activity, new String[]{
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                            }, 1);

                            pendingResult = result;
                            break;
                        }
                    } else {
                        if (ContextCompat.checkSelfPermission(activity,
                                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity,
                                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                            ActivityCompat.requestPermissions(activity,
                                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_COARSE_LOCATION_PERMISSIONS);

                            pendingResult = result;
                            break;
                        }
                    }
                    getBondedDevices(result);

                } catch (Exception ex) {
                    result.error("Error", ex.getMessage(), exceptionToString(ex));
                }

                break;

            case "connect":
                if (arguments.containsKey("address")) {
                    String address = (String) arguments.get("address");
                    connect(result, address);
                } else {
                    result.error("invalid_argument", "argument 'address' not found", null);
                }
                break;

            case "disconnect":
                disconnect(result);
                break;

            case "write":
                if (arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    write(result, message);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "writeBytes":
                if (arguments.containsKey("message")) {
                    byte[] message = (byte[]) arguments.get("message");
                    writeBytes(result, message);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "printCustom":
                if (arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    int size = (int) arguments.get("size");
                    int align = (int) arguments.get("align");
                    String charset = (String) arguments.get("charset");
                    printCustom(result, message, size, align, charset);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "printNewLine":
                printNewLine(result);
                break;

            case "paperCut":
                paperCut(result);
                break;

            case "drawerPin2":
                drawerPin2(result);
                break;

            case "drawerPin5":
                drawerPin5(result);
                break;

            case "printImage":
                if (arguments.containsKey("pathImage")) {
                    String pathImage = (String) arguments.get("pathImage");
                    printImage(result, pathImage);
//          printImageCPCL(result, pathImage, 1, 1);
                } else {
                    result.error("invalid_argument", "argument 'pathImage' not found", null);
                }
                break;

            case "printImageBytes":
                if (arguments.containsKey("bytes")) {
                    byte[] bytes = (byte[]) arguments.get("bytes");
                    printImageBytes(result, bytes);
                } else {
                    result.error("invalid_argument", "argument 'bytes' not found", null);
                }
                break;

            case "printQRcode":
                if (arguments.containsKey("textToQR")) {
                    String textToQR = (String) arguments.get("textToQR");
                    int width = (int) arguments.get("width");
                    int height = (int) arguments.get("height");
                    int align = (int) arguments.get("align");
                    printQRcode(result, textToQR, width, height, align);
                } else {
                    result.error("invalid_argument", "argument 'textToQR' not found", null);
                }
                break;
            case "printLeftRight":
                if (arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    printLeftRight(result, string1, string2, size, charset, format);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;
            case "print3Column":
                if (arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    String string3 = (String) arguments.get("string3");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    print3Column(result, string1, string2, string3, size, charset, format);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;
            case "print4Column":
                if (arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    String string3 = (String) arguments.get("string3");
                    String string4 = (String) arguments.get("string4");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    print4Column(result, string1, string2, string3, string4, size, charset, format);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * @param requestCode  requestCode
     * @param permissions  permissions
     * @param grantResults grantResults
     * @return boolean
     */
    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getBondedDevices(pendingResult);
            } else {
                pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
                pendingResult = null;
            }
            return true;
        }
        return false;
    }

    private void state(Result result) {
        try {
            switch (mBluetoothAdapter.getState()) {
                case BluetoothAdapter.STATE_OFF:
                    result.success(BluetoothAdapter.STATE_OFF);
                    break;
                case BluetoothAdapter.STATE_ON:
                    result.success(BluetoothAdapter.STATE_ON);
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    result.success(BluetoothAdapter.STATE_TURNING_OFF);
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    result.success(BluetoothAdapter.STATE_TURNING_ON);
                    break;
                default:
                    result.success(0);
                    break;
            }
        } catch (SecurityException e) {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }
    }

    /**
     * @param result result
     */
    private void getBondedDevices(Result result) {

        List<Map<String, Object>> list = new ArrayList<>();

        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            Map<String, Object> ret = new HashMap<>();
            ret.put("address", device.getAddress());
            ret.put("name", device.getName());
            ret.put("type", device.getType());
            list.add(ret);
        }

        result.success(list);
    }


    /**
     * @param result  result
     * @param address address
     */
    private void isDeviceConnected(Result result, String address) {

        AsyncTask.execute(() -> {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

                if (device == null) {
                    result.error("connect_error", "device not found", null);
                    return;
                }

                if (THREAD != null && device.ACTION_ACL_CONNECTED.equals(new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).getAction())) {
                    result.success(true);
                } else {
                    result.success(false);
                }

            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }

    private String exceptionToString(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * @param result  result
     * @param address address
     */
    private void connect(Result result, String address) {

        if (THREAD != null) {
            result.error("connect_error", "already connected", null);
            return;
        }
        AsyncTask.execute(() -> {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

                if (device == null) {
                    result.error("connect_error", "device not found", null);
                    return;
                }

                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);

                if (socket == null) {
                    result.error("connect_error", "socket connection not established", null);
                    return;
                }

                // Cancel bt discovery, even though we didn't start it
                mBluetoothAdapter.cancelDiscovery();

                try {
                    socket.connect();
                    THREAD = new ConnectedThread(socket);
                    THREAD.start();
                    result.success(true);
                } catch (Exception ex) {
                    Log.e(TAG, ex.getMessage(), ex);
                    result.error("connect_error", ex.getMessage(), exceptionToString(ex));
                }
            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }

    /**
     * @param result result
     */
    private void disconnect(Result result) {

        if (THREAD == null) {
            result.error("disconnection_error", "not connected", null);
            return;
        }
        AsyncTask.execute(() -> {
            try {
                THREAD.cancel();
                THREAD = null;
                result.success(true);
            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("disconnection_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }

    /**
     * @param result  result
     * @param message message
     */
    private void write(Result result, String message) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            THREAD.write(message.getBytes());
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void writeBytes(Result result, byte[] message) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            THREAD.write(message);
            result.success(message);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printCustom(Result result, String message, int size, int align, String charset) {
        // Print config "mode"
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        byte[] bb5 = new byte[]{0x1B, 0x21, 0x50}; // 5- extra strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
                case 5:
                    THREAD.write(bb5);
            }

            switch (align) {
                case 0:
                    // left align
                    THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
                    break;
                case 1:
                    // center align
                    THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                    break;
                case 2:
                    // right align
                    THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
                    break;
            }
            if (charset != null) {
                THREAD.write(message.getBytes(charset));
            } else {
                THREAD.write(message.getBytes());
            }
            THREAD.write(PrinterCommands.FEED_LINE);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printLeftRight(Result result, String msg1, String msg2, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-15s %15s %n", msg1, msg2);
            if (format != null) {
                line = String.format(format, msg1, msg2);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void print3Column(Result result, String msg1, String msg2, String msg3, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-10s %10s %10s %n", msg1, msg2, msg3);
            if (format != null) {
                line = String.format(format, msg1, msg2, msg3);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void print4Column(Result result, String msg1, String msg2, String msg3, String msg4, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-8s %7s %7s %7s %n", msg1, msg2, msg3, msg4);
            if (format != null) {
                line = String.format(format, msg1, msg2, msg3, msg4);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void printNewLine(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.FEED_LINE);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void paperCut(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.FEED_PAPER_AND_CUT);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void drawerPin2(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.ESC_DRAWER_PIN2);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void drawerPin5(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.ESC_DRAWER_PIN5);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }


    private int xL;
    private int xH;
    private int yL;
    private int yH;


    public void setLHLength(int byteWidth, int iHeight) {
        this.xL = byteWidth % 256;
        this.xH = byteWidth / 256;
        this.yL = iHeight % 256;
        this.yH = iHeight / 256;
        if (this.xH > 255) {
            this.xH = 255;
        }


    }


    private int pow(int n, int times) {
        int retVal = 1;

        for (int i = 0; i < times; ++i) {
            retVal *= n;
        }

        return retVal;
    }


    public byte[] GS_v(int m, int xL, int xH, int yL, int yH, byte[] buf) {
        int length = buf.length;
        byte[] command = new byte[8 + length];
        command[0] = 29;
        command[1] = 118;
        command[2] = 48;
        command[3] = (byte) m;
        command[4] = (byte) xL;
        command[5] = (byte) xH;
        command[6] = (byte) yL;
        command[7] = (byte) yH;
        System.arraycopy(buf, 0, command, 8, length);
        return command;
    }

    public int getByteWidth(int iWidth) {
        int byteWidth = iWidth / 8;
        if (iWidth % 8 != 0) {
            ++byteWidth;
        }

        return byteWidth;
    }

    public byte[] convertDitherBitImage(int[][] tpix, int thresHoldValue, int iRotate, int dither) {
        int height;
        int byteWidth;
        byte[] result;
        int i;
        int index = 0;
        int width = tpix.length;
        height = tpix[0].length;
        byteWidth = this.getByteWidth(width);
        this.setLHLength(byteWidth, height);
        result = new byte[byteWidth * height];
        int x;
        int y;
        label212:
        switch (dither) {
            case 0:
                y = 0;

                while (true) {
                    if (y >= height) {
                        break label212;
                    }

                    for (x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (tpix[x][y] <= thresHoldValue) {
                            result[index] |= (byte) this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                    ++y;
                }
            case 1:
                int TotalCoeffSum = 42;
                long coeff = 0L;
                int[][] ErrorDiffusionTempArray = new int[width + 4][height + 4];
                byte[][] ErrorDiffusionArray = new byte[width + 4][height + 4];

                for (y = 0; y < height; ++y) {
                    for (x = 0; x < width; ++x) {
                        ErrorDiffusionTempArray[x][y] = tpix[x][y];
                    }
                }

                for (y = 0; y < height; ++y) {
                    for (x = 0; x < width; ++x) {
                        int level = ErrorDiffusionTempArray[x][y];
                        long error;
                        if (level > thresHoldValue) {
                            ErrorDiffusionArray[x][y] = 0;
                            error = (long) (level - 255);
                        } else {
                            ErrorDiffusionArray[x][y] = 1;
                            error = (long) level;
                        }

                        long nlevel = (long) ErrorDiffusionTempArray[x][y + 1] + error * 8L / (long) TotalCoeffSum;
                        level = Math.min(255, Math.max(0, (int) nlevel));
                        ErrorDiffusionTempArray[x][y + 1] = level;
                        nlevel = (long) ErrorDiffusionTempArray[x][y + 2] + error * 4L / (long) TotalCoeffSum;
                        level = Math.min(255, Math.max(0, (int) nlevel));
                        ErrorDiffusionTempArray[x][y + 2] = level;

                        for (i = -2; i < 3; ++i) {
                            switch (i) {
                                case -2:
                                    coeff = 2L;
                                    break;
                                case -1:
                                    coeff = 4L;
                                    break;
                                case 0:
                                    coeff = 8L;
                                    break;
                                case 1:
                                    coeff = 4L;
                                    break;
                                case 2:
                                    coeff = 2L;
                            }

                            if (y + i >= 0) {
                                nlevel = (long) ErrorDiffusionTempArray[x + 1][y + i] + error * coeff / (long) TotalCoeffSum;
                                level = Math.min(255, Math.max(0, (int) nlevel));
                                ErrorDiffusionTempArray[x + 1][y + i] = level;
                            }
                        }

                        for (i = -2; i < 3; ++i) {
                            switch (i) {
                                case -2:
                                    coeff = 1L;
                                    break;
                                case -1:
                                    coeff = 2L;
                                    break;
                                case 0:
                                    coeff = 4L;
                                    break;
                                case 1:
                                    coeff = 2L;
                                    break;
                                case 2:
                                    coeff = 1L;
                            }

                            if (y + i >= 0) {
                                nlevel = (long) ErrorDiffusionTempArray[x + 2][y + i] + error * coeff / (long) TotalCoeffSum;
                                level = Math.min(255, Math.max(0, (int) nlevel));
                                ErrorDiffusionTempArray[x + 2][y + i] = level;
                            }
                        }
                    }
                }

                y = 0;

                while (true) {
                    if (y >= height) {
                        break label212;
                    }

                    for (x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (ErrorDiffusionArray[x][y] > 0) {
                            result[index] |= (byte) this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                    ++y;
                }
            case 2:
                byte[][] OrderedDitherArray = new byte[width][height];
                int[][] DitherMatrix = new int[][]{{0, 48, 12, 60, 3, 51, 15, 63}, {32, 16, 44, 28, 35, 19, 47, 31}, {8, 56, 4, 52, 11, 59, 7, 55}, {40, 24, 36, 20, 43, 27, 39, 23}, {2, 50, 14, 62, 1, 49, 13, 61}, {34, 18, 46, 30, 33, 17, 45, 29}, {10, 58, 6, 54, 9, 57, 5, 53}, {42, 26, 38, 22, 41, 25, 37, 21}};
                int[] Intensity = new int[]{0, 1};
                int dth_NumRows = 8;
                int dth_NumCols = 8;
                int dth_NumIntensityLevels = 2;
                int dth_NumRowsLessOne = dth_NumRows - 1;
                int dth_NumColsLessOne = dth_NumCols - 1;
                int dth_RowsXCols = dth_NumRows * dth_NumCols;
                int dth_MaxIntensityVal = 255;
                int dth_MaxDitherIntensityVal = dth_NumRows * dth_NumCols * (dth_NumIntensityLevels - 1);

                for (y = 0; y < height; ++y) {
                    for (x = 0; x < width; ++x) {
                        int DeviceIntensity = tpix[x][y];
                        int DitherIntensity = DeviceIntensity * dth_MaxDitherIntensityVal / dth_MaxIntensityVal;
                        int DitherMatrixIntensity = DitherIntensity % dth_RowsXCols;
                        int Offset = DitherIntensity / dth_RowsXCols;
                        int DitherValue;
                        if (DitherMatrix[y & dth_NumRowsLessOne][x & dth_NumColsLessOne] < DitherMatrixIntensity) {
                            DitherValue = Intensity[1 + Offset];
                        } else {
                            DitherValue = Intensity[0 + Offset];
                        }

                        OrderedDitherArray[x][y] = (byte) DitherValue;
                    }
                }

                for (y = 0; y < height; ++y) {
                    for (x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (OrderedDitherArray[x][y] <= 0) {
                            result[index] |= (byte) this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                }
        }

        if (iRotate != 180) {
            return result;
        } else {
            int cnt = 0;
            int totalSize = byteWidth * height;
            byte[] resultBytes = new byte[totalSize];

            for (i = totalSize - 1; i >= 0; ++cnt) {
                i = result[i] & 255;
                resultBytes[cnt] = this.BitReverseTable256[i];
                --i;
            }

            return resultBytes;
        }
    }

    private final byte[] BitReverseTable256 = new byte[]{0, -128, 64, -64, 32, -96, 96, -32, 16, -112, 80, -48, 48, -80, 112, -16, 8, -120, 72, -56, 40, -88, 104, -24, 24, -104, 88, -40, 56, -72, 120, -8, 4, -124, 68, -60, 36, -92, 100, -28, 20, -108, 84, -44, 52, -76, 116, -12, 12, -116, 76, -52, 44, -84, 108, -20, 28, -100, 92, -36, 60, -68, 124, -4, 2, -126, 66, -62, 34, -94, 98, -30, 18, -110, 82, -46, 50, -78, 114, -14, 10, -118, 74, -54, 42, -86, 106, -22, 26, -102, 90, -38, 58, -70, 122, -6, 6, -122, 70, -58, 38, -90, 102, -26, 22, -106, 86, -42, 54, -74, 118, -10, 14, -114, 78, -50, 46, -82, 110, -18, 30, -98, 94, -34, 62, -66, 126, -2, 1, -127, 65, -63, 33, -95, 97, -31, 17, -111, 81, -47, 49, -79, 113, -15, 9, -119, 73, -55, 41, -87, 105, -23, 25, -103, 89, -39, 57, -71, 121, -7, 5, -123, 69, -59, 37, -91, 101, -27, 21, -107, 85, -43, 53, -75, 117, -11, 13, -115, 77, -51, 45, -83, 109, -19, 29, -99, 93, -35, 61, -67, 125, -3, 3, -125, 67, -61, 35, -93, 99, -29, 19, -109, 83, -45, 51, -77, 115, -13, 11, -117, 75, -53, 43, -85, 107, -21, 27, -101, 91, -37, 59, -69, 123, -5, 7, -121, 71, -57, 39, -89, 103, -25, 23, -105, 87, -41, 55, -73, 119, -9, 15, -113, 79, -49, 47, -81, 111, -17, 31, -97, 95, -33, 63, -65, 127, -1};


    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
//        bm.recycle();
        return resizedBitmap;
    }

    private void printImageCPCL(Result result, String pathImage, int x, int y) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            StringBuffer sb = new StringBuffer();
            sb.append("! 0 200 200 290 1\n");
            sb.append("PAGE-WIDTH 816\n");
            sb.append("JOURNAL\n");
            sb.append("CG 102 282 1 1 ");

            byte[] bytes = sb.toString().getBytes();
            if (pathImage != null) {

                int[][] img = imageLoad(pathImage);


                for (int i = 0; i < img.length; i++) {
                    Log.d("bytes 0", img[i].length + "");

                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();

                byte[] image = convertDitherBitImage(img, 127, 0, 0);

                byte[] endImmage = "\r\n".getBytes();

                output.write(bytes);
                output.write(image);
                output.write(endImmage);
                output.write("PRINT".getBytes());
                output.write("\r\n".getBytes());


                for (byte bit : bytes) {
                    Log.d("bytes 1", bit + "");
                }

                for (byte bit : image) {
                    Log.d("bytes 2", bit + "");
                }

                for (byte bit : endImmage) {
                    Log.d("bytes 3", bit + "");
                }


                THREAD.write(output.toByteArray());


//                THREAD.write(GS_v(0, xL, xH, yL, yH, image));

            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    public Bitmap getImage(String filepath) throws IOException {
        return BitmapFactory.decodeFile(filepath);
    }


    public int[][] imageLoad(String filepath) throws IOException {
        int[][] array = null;
        Bitmap image = this.getImage(filepath);
        if (image != null) {
            array = this.getByteArray(image);
        }

        return array;
    }


    public byte[] convertBitImage(int[][] tpix, int thresHoldValue, int halfTone) {
        int index = 0;
        int x = 0;
        int y = 0;
        int width = tpix.length;
        int height = tpix[0].length;
        int byteWidth = this.getByteWidth(width);
        this.setLHLength(byteWidth, height);
        byte[] result = new byte[byteWidth * height];
       
        switch (halfTone) {
            case 0:
                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (tpix[x][y] <= thresHoldValue) {
                            result[index] |= (byte)this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                }

                return result;
            case 1:
                int TotalCoeffSum = 42;
                long coeff = 0L;
                int[][] ErrorDiffusionTempArray = new int[width + 4][height + 4];
                byte[][] ErrorDiffusionArray = new byte[width + 4][height + 4];

                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        ErrorDiffusionTempArray[x][y] = tpix[x][y];
                    }
                }

                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        int level = ErrorDiffusionTempArray[x][y];
                        long error;
                        if (level > thresHoldValue) {
                            ErrorDiffusionArray[x][y] = 0;
                            error = (long)(level - 255);
                        } else {
                            ErrorDiffusionArray[x][y] = 1;
                            error = (long)level;
                        }

                        long nlevel = (long)ErrorDiffusionTempArray[x][y + 1] + error * 8L / (long)TotalCoeffSum;
                        level = Math.min(255, Math.max(0, (int)nlevel));
                        ErrorDiffusionTempArray[x][y + 1] = level;
                        nlevel = (long)ErrorDiffusionTempArray[x][y + 2] + error * 4L / (long)TotalCoeffSum;
                        level = Math.min(255, Math.max(0, (int)nlevel));
                        ErrorDiffusionTempArray[x][y + 2] = level;

                        int i;
                        for(i = -2; i < 3; ++i) {
                            switch (i) {
                                case -2:
                                    coeff = 2L;
                                    break;
                                case -1:
                                    coeff = 4L;
                                    break;
                                case 0:
                                    coeff = 8L;
                                    break;
                                case 1:
                                    coeff = 4L;
                                    break;
                                case 2:
                                    coeff = 2L;
                            }

                            if (y + i >= 0) {
                                nlevel = (long)ErrorDiffusionTempArray[x + 1][y + i] + error * coeff / (long)TotalCoeffSum;
                                level = Math.min(255, Math.max(0, (int)nlevel));
                                ErrorDiffusionTempArray[x + 1][y + i] = level;
                            }
                        }

                        for(i = -2; i < 3; ++i) {
                            switch (i) {
                                case -2:
                                    coeff = 1L;
                                    break;
                                case -1:
                                    coeff = 2L;
                                    break;
                                case 0:
                                    coeff = 4L;
                                    break;
                                case 1:
                                    coeff = 2L;
                                    break;
                                case 2:
                                    coeff = 1L;
                            }

                            if (y + i >= 0) {
                                nlevel = (long)ErrorDiffusionTempArray[x + 2][y + i] + error * coeff / (long)TotalCoeffSum;
                                level = Math.min(255, Math.max(0, (int)nlevel));
                                ErrorDiffusionTempArray[x + 2][y + i] = level;
                            }
                        }
                    }
                }

                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (ErrorDiffusionArray[x][y] > 0) {
                            result[index] |= (byte)this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                }

                return result;
            case 2:
                byte[][] OrderedDitherArray = new byte[width][height];
                int[][] DitherMatrix = new int[][]{{0, 48, 12, 60, 3, 51, 15, 63}, {32, 16, 44, 28, 35, 19, 47, 31}, {8, 56, 4, 52, 11, 59, 7, 55}, {40, 24, 36, 20, 43, 27, 39, 23}, {2, 50, 14, 62, 1, 49, 13, 61}, {34, 18, 46, 30, 33, 17, 45, 29}, {10, 58, 6, 54, 9, 57, 5, 53}, {42, 26, 38, 22, 41, 25, 37, 21}};
                int[] Intensity = new int[]{0, 1};
                int dth_NumRows = 8;
                int dth_NumCols = 8;
                int dth_NumIntensityLevels = 2;
                int dth_NumRowsLessOne = dth_NumRows - 1;
                int dth_NumColsLessOne = dth_NumCols - 1;
                int dth_RowsXCols = dth_NumRows * dth_NumCols;
                int dth_MaxIntensityVal = 255;
                int dth_MaxDitherIntensityVal = dth_NumRows * dth_NumCols * (dth_NumIntensityLevels - 1);

                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        int DeviceIntensity = tpix[x][y];
                        int DitherIntensity = DeviceIntensity * dth_MaxDitherIntensityVal / dth_MaxIntensityVal;
                        int DitherMatrixIntensity = DitherIntensity % dth_RowsXCols;
                        int Offset = DitherIntensity / dth_RowsXCols;
                        int DitherValue;
                        if (DitherMatrix[y & dth_NumRowsLessOne][x & dth_NumColsLessOne] < DitherMatrixIntensity) {
                            DitherValue = Intensity[1 + Offset];
                        } else {
                            DitherValue = Intensity[0 + Offset];
                        }

                        OrderedDitherArray[x][y] = (byte)DitherValue;
                    }
                }

                for(y = 0; y < height; ++y) {
                    for(x = 0; x < width; ++x) {
                        if (x != 0 && x % 8 == 0) {
                            ++index;
                        }

                        if (OrderedDitherArray[x][y] <= 0) {
                            result[index] |= (byte)this.pow(2, 7 - x % 8);
                        }
                    }

                    ++index;
                }
        }

        return result;
    }


    private void printImage(Result result, String pathImage) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }


        byte[] resetCmd = new byte[]{27, 64};
        THREAD.write(resetCmd);

        THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);


        try {
            Bitmap bmp = BitmapFactory.decodeFile(pathImage);
            if (bmp != null) {


                Bitmap bmp2 = getResizedBitmap(bmp, 816, bmp.getHeight());

                byte[] image = convertBitImage(getByteArray(bmp), 127, 0);


                THREAD.write(GS_v(0, xL, xH, yL, yH, image));


            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private int convertRGB(int ARGB) {
        int[] rgb = new int[]{(ARGB & 16711680) >> 16, (ARGB & '\uff00') >> 8, ARGB & 255};
        int rgbVal = (((rgb[0] * 3) + (rgb[1] * 6)) + rgb[2]) / 10;
        return rgbVal;
    }

    public int[][] getByteArray(Bitmap image) {
        int maxColor = 0;
        int minColor = 1;
        int width = image.getWidth();
        int height = image.getHeight();
        int[][] tpix = new int[width][height];

        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                tpix[x][y] = this.convertRGB(image.getPixel(x, y));
            }
        }

        return tpix;
    }


    private void printImageBytes(Result result, byte[] bytes) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printQRcode(Result result, String textToQR, int width, int height, int align) {
        MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (align) {
                case 0:
                    // left align
                    THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
                    break;
                case 1:
                    // center align
                    THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                    break;
                case 2:
                    // right align
                    THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
                    break;
            }
            BitMatrix bitMatrix = multiFormatWriter.encode(textToQR, BarcodeFormat.QR_CODE, width, height);
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            Bitmap bmp = barcodeEncoder.createBitmap(bitMatrix);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
                e.printStackTrace();

                try {
                    tmpIn = socket.getInputStream();
                    tmpOut = socket.getOutputStream();

                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }

            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    readSink.success(new String(buffer, 0, bytes));
                } catch (NullPointerException e) {
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                Log.e("Error2", e.getMessage(), e);

                e.printStackTrace();
            }
        }

        public void cancel() {
            try {
                outputStream.flush();
                outputStream.close();

                inputStream.close();

                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final StreamHandler stateStreamHandler = new StreamHandler() {

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                Log.d(TAG, action);

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    THREAD = null;
                    statusSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    statusSink.success(1);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action)) {
                    THREAD = null;
                    statusSink.success(2);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    THREAD = null;
                    statusSink.success(0);
                }
            }
        };

        @Override
        public void onListen(Object o, EventSink eventSink) {
            statusSink = eventSink;
            context.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));

        }

        @Override
        public void onCancel(Object o) {
            statusSink = null;
            context.unregisterReceiver(mReceiver);
        }
    };

    private final StreamHandler readResultsHandler = new StreamHandler() {
        @Override
        public void onListen(Object o, EventSink eventSink) {
            readSink = eventSink;
        }

        @Override
        public void onCancel(Object o) {
            readSink = null;
        }
    };
}