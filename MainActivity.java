package tr.com.m.uvccamera;


import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.microblink.directApi.DirectApiErrorListener;
import com.microblink.directApi.RecognizerRunner;
import com.microblink.entities.recognizers.Recognizer;
import com.microblink.entities.recognizers.RecognizerBundle;
import com.microblink.entities.recognizers.blinkid.mrtd.MrtdRecognizer;
import com.microblink.hardware.orientation.Orientation;
import com.microblink.image.Image;
import com.microblink.image.ImageBuilder;
import com.microblink.recognition.FeatureNotSupportedException;
import com.microblink.recognition.RecognitionSuccessType;
import com.microblink.view.recognition.ScanResultListener;
import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import asd.video.Encoder;
import asd.video.Encoder.EncodeListener;
import asd.video.SurfaceEncoder;
import asd.widget.SimpleUVCCameraTextureView;

public final class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent {
    private static final boolean DEBUG = true;	// set false when releasing
    private static final String TAG = "MainActivity";
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;

    private static final int CAPTURE_STOP = 0;
    private static final int CAPTURE_PREPARE = 1;
    private static final int CAPTURE_RUNNING = 2;

    private final Object mSync = new Object();
    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private SimpleUVCCameraTextureView mUVCCameraView;
    // for open&start / stop&close camera preview
    private ToggleButton mCameraButton;
    // for start & stop movie capture
    private ImageButton mCaptureButton;

    private int mCaptureState = 0;
    private Surface mPreviewSurface;

    //private ImageView uvc_image;

    private RecognizerRunner mRecognizerRunner;
    private MrtdRecognizer mRecognizer;
    private RecognizerBundle mRecognizerBundle;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        //uvc_image = (ImageView)findViewById(R.id.callback_imageview);

        mCameraButton = (ToggleButton)findViewById(R.id.camera_button);
        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);

        mCaptureButton = (ImageButton)findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(mOnClickListener);

        mUVCCameraView = (SimpleUVCCameraTextureView)findViewById(R.id.UVCCameraTextureView1);
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);
        mUVCCameraView.setSurfaceTextureListener(mSurfaceTextureListener);

        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);

        // initialize your activity here
        // create MrtdRecognizer
        mRecognizer = new MrtdRecognizer();

        // bundle recognizers into RecognizerBundle
        mRecognizerBundle = new RecognizerBundle(mRecognizer);

        try {
            mRecognizerRunner = RecognizerRunner.getSingletonInstance();
        } catch (FeatureNotSupportedException e) {
            Toast.makeText(this, "Feature not supported! Reason: " + e.getReason().getDescription(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mRecognizerRunner.initialize(this, mRecognizerBundle, new DirectApiErrorListener() {
            @Override
            public void onRecognizerError(Throwable t) {
                Toast.makeText(MainActivity.this, "There was an error in initialization of Recognizer: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        synchronized (mSync) {
            if (mUSBMonitor != null) {
                mUSBMonitor.register();
            }
            if (mUVCCamera != null)
                mUVCCamera.startPreview();
        }
        setCameraButton(false);
        updateItems();
    }

    @Override
    protected void onStop() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                stopCapture();
                mUVCCamera.stopPreview();
            }
            mUSBMonitor.unregister();
        }
        setCameraButton(false);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.destroy();
                mUVCCamera = null;
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.destroy();
                mUSBMonitor = null;
            }
        }
        mCameraButton = null;
        mCaptureButton = null;
        mUVCCameraView = null;
        mRecognizerRunner.terminate();
        super.onDestroy();
    }

    private final OnCheckedChangeListener mOnCheckedChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
            synchronized (mSync) {
                if (isChecked && mUVCCamera == null) {
                    CameraDialog.showDialog(MainActivity.this);
                } else if (mUVCCamera != null) {
                    mUVCCamera.destroy();
                    mUVCCamera = null;
                }
            }
            updateItems();
        }
    };

    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(final View v) {
            if (checkPermissionWriteExternalStorage()) {
                if (mCaptureState == CAPTURE_STOP) {
                    startCapture();
                } else {
                    stopCapture();
                }
            }
        }
    };

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.destroy();
                    mUVCCamera = null;
                }
            }
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    final UVCCamera camera = new UVCCamera();
                    camera.open(ctrlBlock);
                    if (DEBUG) Log.i(TAG, "supportedSize:" + camera.getSupportedSize());
                    if (mPreviewSurface != null) {
                        mPreviewSurface.release();
                        mPreviewSurface = null;
                    }
                    //try {
                    //    camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                    //} catch (final IllegalArgumentException e) {
                    try {
                        // fallback to YUV mode
                        camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                    } catch (final IllegalArgumentException e1) {
                        camera.destroy();
                        return;
                    }
                    //}
                    final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
                    if (st != null) {
                        mPreviewSurface = new Surface(st);
                        camera.setPreviewDisplay(mPreviewSurface);
                        camera.startPreview();
                        camera.setFrameCallback(mFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
                    }
                    synchronized (mSync) {
                        mUVCCamera = camera;
                    }
                }
            }, 0);
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            // XXX you should check whether the comming device equal to camera device that currently using
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    synchronized (mSync) {
                        if (mUVCCamera != null) {
                            mUVCCamera.close();
                        }
                    }
                    if (mPreviewSurface != null) {
                        mPreviewSurface.release();
                        mPreviewSurface = null;
                    }
                }
            }, 0);
            setCameraButton(false);
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            setCameraButton(false);
        }
    };

    /**
     * to access from CameraDialog
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        if (canceled) {
            setCameraButton(false);
        }
    }

    private void setCameraButton(final boolean isOn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mCameraButton != null) {
                    try {
                        mCameraButton.setOnCheckedChangeListener(null);
                        mCameraButton.setChecked(isOn);
                    } finally {
                        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
                    }
                }
                if (!isOn && (mCaptureButton != null)) {
                    mCaptureButton.setVisibility(View.INVISIBLE);
                }
            }
        }, 0);
    }

    //**********************************************************************
    private final SurfaceTextureListener mSurfaceTextureListener = new SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
            if (mEncoder != null && mCaptureState == CAPTURE_RUNNING) {
                mEncoder.frameAvailable();
            }
        }
    };

    private Encoder mEncoder;
    /**
     * start capturing
     */
    private final void startCapture() {
        if (DEBUG) Log.v(TAG, "startCapture:");
        if (mEncoder == null && (mCaptureState == CAPTURE_STOP)) {
            mCaptureState = CAPTURE_PREPARE;
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    final String path = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4");
                    if (!TextUtils.isEmpty(path)) {
                        mEncoder = new SurfaceEncoder(path);
                        mEncoder.setEncodeListener(mEncodeListener);
                        try {
                            mEncoder.prepare();
                            mEncoder.startRecording();
                        } catch (final IOException e) {
                            mCaptureState = CAPTURE_STOP;
                        }
                    } else
                        throw new RuntimeException("Failed to start capture.");
                }
            }, 0);
            updateItems();
        }
    }

    /**
     * stop capture if capturing
     */
    private final void stopCapture() {
        if (DEBUG) Log.v(TAG, "stopCapture:");
        queueEvent(new Runnable() {
            @Override
            public void run() {
                synchronized (mSync) {
                    if (mUVCCamera != null) {
                        mUVCCamera.stopCapture();
                    }
                }
                if (mEncoder != null) {
                    mEncoder.stopRecording();
                    mEncoder = null;
                }
            }
        }, 0);
    }

    /**
     * callbackds from Encoder
     */
    private final EncodeListener mEncodeListener = new EncodeListener() {
        @Override
        public void onPreapared(final Encoder encoder) {
            if (DEBUG) Log.v(TAG, "onPreapared:");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.startCapture(((SurfaceEncoder)encoder).getInputSurface());
                }
            }
            mCaptureState = CAPTURE_RUNNING;
        }

        @Override
        public void onRelease(final Encoder encoder) {
            if (DEBUG) Log.v(TAG, "onRelease:");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopCapture();
                }
            }
            mCaptureState = CAPTURE_STOP;
            updateItems();
        }
    };

    private void updateItems() {
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCaptureButton.setVisibility(mCameraButton.isChecked() ? View.VISIBLE : View.INVISIBLE);
                mCaptureButton.setColorFilter(mCaptureState == CAPTURE_STOP ? 0 : 0xffff0000);
            }
        });
    }

    /**
     * create file path for saving movie / still image file
     * @param type Environment.DIRECTORY_MOVIES / Environment.DIRECTORY_DCIM
     * @param ext .mp4 / .png
     * @return return null if can not write to storage
     */
    private static final String getCaptureFile(final String type, final String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(type), "USBCameraTest");
        dir.mkdirs();	// create directories if they do not exist
        if (dir.canWrite()) {
            return (new File(dir, getDateTimeString() + ext)).toString();
        }
        return null;
    }

    private static final SimpleDateFormat sDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
    private static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return sDateTimeFormat.format(now.getTime());
    }

    private Bitmap tempBitmap;
    private final IFrameCallback mFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            synchronized (mSync) {
                /*if (tempBitmap == null) {
                    tempBitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
                }*/
                // reset position to 0
                frame.clear();
                final byte[] arr = new byte[frame.remaining()];
                //Log.d("RASPBERRY", "-------REMAINING-------" + Integer.toString(frame.remaining()));
                frame.get(arr);
                frame.clear();
                //tempBitmap.copyPixelsFromBuffer(frame);
                //Log.d("RASPBERRY", "-------111111111-------");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mRecognizerRunner.getCurrentState() == RecognizerRunner.State.READY) {
                            Image img = ImageBuilder.buildImageFromCamera1NV21Frame(arr, PREVIEW_WIDTH, PREVIEW_HEIGHT, Orientation.ORIENTATION_LANDSCAPE_RIGHT, null);
                            mRecognizerRunner.recognizeImage(img, mScanResultListener);
                            //Log.d("RASPBERRY", "-------222222222-------");
                        }
                    }
                });
            }
            //Toast.makeText(MainActivity.this, "ALT", Toast.LENGTH_LONG).show();

            //uvc_image.post(mUpdateImageTask);
        }
    };

    private Runnable mUpdateImageTask = new Runnable() {
        @Override
        public void run() {
            synchronized (mSync) {
                //uvc_image.setImageBitmap(tempBitmap);
            }
        }
    };

    private final ScanResultListener mScanResultListener = new ScanResultListener() {
        @Override
        public void onScanningDone(@NonNull RecognitionSuccessType recognitionSuccessType) {
            // this method is from ScanResultListener and will be called
            // when scanning completes
            // you can obtain scanning result by calling getResult on each
            // recognizer that you bundled into RecognizerBundle.
            // for example:

            //Log.d("RASPBERRY", "SCANNING DONE!!!!!!!");


            final MrtdRecognizer.Result result = mRecognizer.getResult();
            if (result.getResultState() == Recognizer.Result.State.Valid) {
                // result is valid, you can use it however you wish
                //Toast.makeText(MainActivity.this, "RESULT IS VALID!", Toast.LENGTH_LONG).show();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d("RASPBERRY", result.getMrzResult().getPrimaryId());
                        Log.d("RASPBERRY", result.getMrzResult().getSecondaryId());
                        Toast.makeText(MainActivity.this, result.getMrzResult().getPrimaryId(), Toast.LENGTH_LONG).show();
                        Toast.makeText(MainActivity.this, result.getMrzResult().getSecondaryId(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };
}
