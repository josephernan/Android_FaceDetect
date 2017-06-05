package com.psjdc.mobilefacedetection;

import java.util.ArrayList;
import java.util.List;

import org.opencv.R;
import org.opencv.android.FpsMeter;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Face;
import android.hardware.Camera.FaceDetectionListener;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;

public class CameraPreview extends SurfaceView implements
		SurfaceHolder.Callback, PreviewCallback, FaceDetectionListener {

	private FdActivity detectionActivity;
	
	SurfaceHolder mHolder;
	Camera mCamera;

	private static final int MAGIC_TEXTURE_ID = 10;

	private byte mBuffer[];
	private Mat[] mFrameChain;
	private int mChainIdx = 0;
	private Thread mThread;
	private boolean mStopThread;

	protected JavaCameraFrame[] mCameraFrame;
	private SurfaceTexture mSurfaceTexture;

	private static final String TAG = "CameraBridge";
	private static final int MAX_UNSPECIFIED = -1;
	private static final int STOPPED = 0;
	private static final int STARTED = 1;

	private int mState = STOPPED;
	private Bitmap mCacheBitmap;
	private CvCameraViewListener2 mListener;
	private boolean mSurfaceExist;
	private Object mSyncObject = new Object();

	protected int mFrameWidth;
	protected int mFrameHeight;
	protected int mMaxHeight;
	protected int mMaxWidth;
	protected float mScale = 0;
	protected int mPreviewFormat = Highgui.CV_CAP_ANDROID_COLOR_FRAME_RGB;
	protected int mCameraIndex = CAMERA_ID_BACK;
	protected boolean mEnabled;
	protected FpsMeter mFpsMeter = null;

	public static final int CAMERA_ID_BACK = 99;
	public static final int CAMERA_ID_FRONT = 98;

	public static final boolean USE_CUSTOM_DETECTION = false;
	
	Mat tempRgbMat = null;
	
	ArrayList<Rect> facesRectList = new ArrayList<Rect>();

	Paint mDetectionPaint = new Paint();
	public CameraPreview(Context context, int cameraId) {
		super(context);

		mCameraIndex = cameraId;
		getHolder().addCallback(this);
		mMaxWidth = MAX_UNSPECIFIED;
		mMaxHeight = MAX_UNSPECIFIED;

		initDetectionPaint();
	}

	public CameraPreview(Context context, AttributeSet attrs) {
		super(context, attrs);

		// Install a SurfaceHolder.Callback so we get notified when the
		// underlying surface is created and destroyed.
		mHolder = getHolder();
		mHolder.addCallback(this);
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		int count = attrs.getAttributeCount();
		Log.d(TAG, "Attr count: " + Integer.valueOf(count));

		TypedArray styledAttrs = getContext().obtainStyledAttributes(attrs,
				R.styleable.CameraBridgeViewBase);
		if (styledAttrs.getBoolean(R.styleable.CameraBridgeViewBase_show_fps,
				false))
			enableFpsMeter();

		mCameraIndex = styledAttrs.getInt(
				R.styleable.CameraBridgeViewBase_camera_id, -1);

		mMaxWidth = MAX_UNSPECIFIED;
		mMaxHeight = MAX_UNSPECIFIED;
		styledAttrs.recycle();
		
		initDetectionPaint();
	}
	
	private void initDetectionPaint() {
		mDetectionPaint.setStrokeWidth(2);
		mDetectionPaint.setStyle(Style.STROKE);
		mDetectionPaint.setColor(Color.GREEN);
	}
	
	public void surfaceCreated(SurfaceHolder holder) {
		// The Surface has been created, acquire the camera and tell it where
		// to draw.
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		// Surface will be destroyed when we return, so stop the preview.
		// Because the CameraDevice object is not a shared resource, it's very
		// important to release it when the activity is paused.
		synchronized (mSyncObject) {
			mSurfaceExist = false;
			checkCurrentState();
		}
	}

	private Size getOptimalPreviewSize(
			List<android.hardware.Camera.Size> sizes, int w, int h) {
		final double ASPECT_TOLERANCE = 0.05;
		double targetRatio = (double) w / h;
		if (sizes == null)
			return null;

		Size optimalSize = null;
		double minDiff = Double.MAX_VALUE;

		int targetHeight = h;

		// Try to find an size match aspect ratio and size
		for (android.hardware.Camera.Size size : sizes) {
			double ratio = (double) size.width / size.height;
			if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
				continue;
			if (Math.abs(size.height - targetHeight) < minDiff) {
				optimalSize = new Size(size.width, size.height);
				minDiff = Math.abs(size.height - targetHeight);
			}
		}

		// Cannot find the one match the aspect ratio, ignore the requirement
		if (optimalSize == null) {
			minDiff = Double.MAX_VALUE;
			for (android.hardware.Camera.Size size : sizes) {
				if (Math.abs(size.height - targetHeight) < minDiff) {
					optimalSize = new Size(size.width, size.height);
					minDiff = Math.abs(size.height - targetHeight);
				}
			}
		}
		return optimalSize;
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		// Now that the size is known, set up the camera parameters and begin
		// the preview.
		synchronized (mSyncObject) {
			if (!mSurfaceExist) {
				mSurfaceExist = true;
				checkCurrentState();
			} else {
				/**
				 * Surface changed. We need to stop camera and restart with new
				 * parameters
				 */
				/* Pretend that old surface has been destroyed */
				mSurfaceExist = false;
				checkCurrentState();
				/* Now use new surface. Say we have it now */
				mSurfaceExist = true;
				checkCurrentState();
			}
		}
	}
	
	protected boolean initializeCamera(int width, int height) {
		Log.d(TAG, "Initialize java camera");
		boolean result = true;
		synchronized (this) {
			mCamera = null;

			int localCameraIndex = -1;
			Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
			for (int camIdx = 0; camIdx < Camera
					.getNumberOfCameras(); ++camIdx) {
				Camera.getCameraInfo(camIdx, cameraInfo);
				if (mCameraIndex == CAMERA_ID_BACK && cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
					localCameraIndex = camIdx;
					break;
				} else if (mCameraIndex == CAMERA_ID_FRONT && cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
					localCameraIndex = camIdx;
					mCameraIndex = CAMERA_ID_FRONT;
					break;
				}
			}
			
			if (localCameraIndex == -1) {
				mCamera = Camera.open();
				mCameraIndex = CAMERA_ID_BACK;

				if (mCamera == null
						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
					boolean connected = false;
					for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
						Log.d(TAG, "Trying to open camera with new open("
								+ Integer.valueOf(camIdx) + ")");
						try {
							mCamera = Camera.open(camIdx);
							connected = true;
							Camera.getCameraInfo(camIdx, cameraInfo);
							if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
								mCameraIndex = CAMERA_ID_BACK;
							else
								mCameraIndex = CAMERA_ID_FRONT;
						} catch (RuntimeException e) {
							Log.e(TAG, "Camera #" + camIdx + "failed to open: "
									+ e.getLocalizedMessage());
						}
						if (connected)
							break;
					}
				}
			} else {
				mCamera = Camera.open(localCameraIndex);
			}

			if (mCamera == null)
				return false;

			/* Now set camera parameters */
			try {
				Camera.Parameters params = mCamera.getParameters();
				Log.d(TAG, "getSupportedPreviewSizes()");
				List<android.hardware.Camera.Size> sizes = params
						.getSupportedPreviewSizes();
				if (sizes != null) {
					/*
					 * Select the size that fits surface considering maximum
					 * size allowed
					 */
					Size frameSize;
					if (true) {
						frameSize = getOptimalPreviewSize(sizes, width, height);
					} else {
						frameSize = calculateCameraFrameSize(sizes, width, height);
					}

					params.setPreviewFormat(ImageFormat.NV21);
					Log.d(TAG,
							"Set preview size to "
									+ Integer.valueOf((int) frameSize.width)
									+ "x"
									+ Integer.valueOf((int) frameSize.height));

					params.setPreviewSize((int) frameSize.width,
							(int) frameSize.height);

					List<String> FocusModes = params.getSupportedFocusModes();
					if (FocusModes != null
							&& FocusModes
									.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
					}

					mCamera.setParameters(params);
					params = mCamera.getParameters();

					mFrameWidth = params.getPreviewSize().width;
					mFrameHeight = params.getPreviewSize().height;

					if ((getLayoutParams().width == LayoutParams.MATCH_PARENT)
							&& (getLayoutParams().height == LayoutParams.MATCH_PARENT))
						mScale = Math.min(((float) height) / mFrameHeight,
								((float) width) / mFrameWidth);
					else
						mScale = 0;

					if (mFpsMeter != null) {
						mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
					}

					int size = mFrameWidth * mFrameHeight;
					size = size
							* ImageFormat.getBitsPerPixel(params
									.getPreviewFormat()) / 8;
					mBuffer = new byte[size];

					mCamera.addCallbackBuffer(mBuffer);
					mCamera.setPreviewCallbackWithBuffer(this);

					mFrameChain = new Mat[2];
					mFrameChain[0] = new Mat(mFrameHeight + (mFrameHeight / 2),
							mFrameWidth, CvType.CV_8UC1);
					mFrameChain[1] = new Mat(mFrameHeight + (mFrameHeight / 2),
							mFrameWidth, CvType.CV_8UC1);

					AllocateCache();

					mCameraFrame = new JavaCameraFrame[2];
					mCameraFrame[0] = new JavaCameraFrame(mFrameChain[0],
							mFrameWidth, mFrameHeight);
					mCameraFrame[1] = new JavaCameraFrame(mFrameChain[1],
							mFrameWidth, mFrameHeight);

					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
						mSurfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
						mCamera.setPreviewTexture(mSurfaceTexture);
					} else
						mCamera.setPreviewDisplay(null);

					/* Finally we are ready to start the preview */
					Log.d(TAG, "startPreview");
					mCamera.startPreview();
					if (!USE_CUSTOM_DETECTION) {
						mCamera.startFaceDetection();
						mCamera.setFaceDetectionListener(this);
					}
				} else
					result = false;
			} catch (Exception e) {
				result = false;
				e.printStackTrace();
			}
		}

		return result;
	}

	protected void releaseCamera() {
		synchronized (this) {
			if (mCamera != null) {
				if (!USE_CUSTOM_DETECTION) {
					mCamera.setFaceDetectionListener(null);
					mCamera.stopFaceDetection();
				}
				mCamera.stopPreview();
				mCamera.setPreviewCallback(null);

				mCamera.release();
			}
			mCamera = null;
			if (mFrameChain != null) {
				mFrameChain[0].release();
				mFrameChain[1].release();
			}
			if (mCameraFrame != null) {
				mCameraFrame[0].release();
				mCameraFrame[1].release();
			}
		}
	}

	protected boolean connectCamera(int width, int height) {

		/*
		 * 1. We need to instantiate camera 2. We need to start thread which
		 * will be getting frames
		 */
		/* First step - initialize camera connection */
		Log.d(TAG, "Connecting to camera");
		if (!initializeCamera(width, height))
			return false;

		/* now we can start update thread */
		Log.d(TAG, "Starting processing thread");
		mStopThread = false;
		mThread = new Thread(new CameraWorker());
		mThread.start();

		return true;
	}

	protected void disconnectCamera() {
		/*
		 * 1. We need to stop thread which updating the frames 2. Stop camera
		 * and release it
		 */
		Log.d(TAG, "Disconnecting from camera");
		try {
			mStopThread = true;
			Log.d(TAG, "Notify thread");
			synchronized (this) {
				this.notify();
			}
			Log.d(TAG, "Wating for thread");
			if (mThread != null)
				mThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			mThread = null;
		}

		/* Now release camera */
		releaseCamera();
	}

	public void onPreviewFrame(byte[] frame, Camera arg1) {
		Log.d(TAG, "Preview Frame received. Frame size: " + frame.length);
		synchronized (this) {
			mFrameChain[1 - mChainIdx].put(0, 0, frame);
			this.notify();
		}
		if (mCamera != null)
			mCamera.addCallbackBuffer(mBuffer);
	}

	/**
	 * Sets the camera index
	 * 
	 * @param cameraIndex
	 *            new camera index
	 */
	public void setCameraIndex(int cameraIndex) {
		this.mCameraIndex = cameraIndex;
	}
	
	public int getCameraIndex() {
		return mCameraIndex;
	}

	public interface CvCameraViewListener {
		/**
		 * This method is invoked when camera preview has started. After this
		 * method is invoked the frames will start to be delivered to client via
		 * the onCameraFrame() callback.
		 * 
		 * @param width
		 *            - the width of the frames that will be delivered
		 * @param height
		 *            - the height of the frames that will be delivered
		 */
		public void onCameraViewStarted(int width, int height);

		/**
		 * This method is invoked when camera preview has been stopped for some
		 * reason. No frames will be delivered via onCameraFrame() callback
		 * after this method is called.
		 */
		public void onCameraViewStopped();

		/**
		 * This method is invoked when delivery of the frame needs to be done.
		 * The returned values - is a modified frame which needs to be displayed
		 * on the screen. TODO: pass the parameters specifying the format of the
		 * frame (BPP, YUV or RGB and etc)
		 */
		public Mat onCameraFrame(Mat inputFrame);
	}

	public interface CvCameraViewListener2 {
		/**
		 * This method is invoked when camera preview has started. After this
		 * method is invoked the frames will start to be delivered to client via
		 * the onCameraFrame() callback.
		 * 
		 * @param width
		 *            - the width of the frames that will be delivered
		 * @param height
		 *            - the height of the frames that will be delivered
		 */
		public void onCameraViewStarted(int width, int height);

		/**
		 * This method is invoked when camera preview has been stopped for some
		 * reason. No frames will be delivered via onCameraFrame() callback
		 * after this method is called.
		 */
		public void onCameraViewStopped();

		/**
		 * This method is invoked when delivery of the frame needs to be done.
		 * The returned values - is a modified frame which needs to be displayed
		 * on the screen. TODO: pass the parameters specifying the format of the
		 * frame (BPP, YUV or RGB and etc)
		 */
		public Mat onCameraFrame(CvCameraViewFrame inputFrame);
	};

	/**
	 * This method is provided for clients, so they can enable the camera
	 * connection. The actual onCameraViewStarted callback will be delivered
	 * only after both this method is called and surface is available
	 */
	public void enableView() {
		synchronized (mSyncObject) {
			mEnabled = true;
			checkCurrentState();
		}
	}

	/**
	 * This method is provided for clients, so they can disable camera
	 * connection and stop the delivery of frames even though the surface view
	 * itself is not destroyed and still stays on the scren
	 */
	public void disableView() {
		synchronized (mSyncObject) {
			mEnabled = false;
			checkCurrentState();
		}
	}

	/**
	 * This method enables label with fps value on the screen
	 */
	public void enableFpsMeter() {
		if (mFpsMeter == null) {
			mFpsMeter = new FpsMeter();
			mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
		}
	}

	public void disableFpsMeter() {
		mFpsMeter = null;
	}

	/**
	 * 
	 * @param listener
	 */

	public void setCvCameraViewListener(CvCameraViewListener2 listener) {
		mListener = listener;
	}

	public void setCvCameraViewListener(CvCameraViewListener listener) {
		CvCameraViewListenerAdapter adapter = new CvCameraViewListenerAdapter(
				listener);
		adapter.setFrameFormat(mPreviewFormat);
		mListener = adapter;
	}

	/**
	 * This method sets the maximum size that camera frame is allowed to be.
	 * When selecting size - the biggest size which less or equal the size set
	 * will be selected. As an example - we set setMaxFrameSize(200,200) and we
	 * have 176x152 and 320x240 sizes. The preview frame will be selected with
	 * 176x152 size. This method is useful when need to restrict the size of
	 * preview frame for some reason (for example for video recording)
	 * 
	 * @param maxWidth
	 *            - the maximum width allowed for camera frame.
	 * @param maxHeight
	 *            - the maximum height allowed for camera frame
	 */
	public void setMaxFrameSize(int maxWidth, int maxHeight) {
		mMaxWidth = maxWidth;
		mMaxHeight = maxHeight;
	}

	public void SetCaptureFormat(int format) {
		mPreviewFormat = format;
		if (mListener instanceof CvCameraViewListenerAdapter) {
			CvCameraViewListenerAdapter adapter = (CvCameraViewListenerAdapter) mListener;
			adapter.setFrameFormat(mPreviewFormat);
		}
	}

	/**
	 * Called when mSyncObject lock is held
	 */
	private void checkCurrentState() {
		int targetState;

		if (mEnabled && mSurfaceExist && getVisibility() == VISIBLE) {
			targetState = STARTED;
		} else {
			targetState = STOPPED;
		}

		if (targetState != mState) {
			/*
			 * The state change detected. Need to exit the current state and
			 * enter target state
			 */
			processExitState(mState);
			mState = targetState;
			processEnterState(mState);
		}
	}

	private void processEnterState(int state) {
		switch (state) {
		case STARTED:
			onEnterStartedState();
			if (mListener != null) {
				mListener.onCameraViewStarted(mFrameWidth, mFrameHeight);
			}
			break;
		case STOPPED:
			onEnterStoppedState();
			if (mListener != null) {
				mListener.onCameraViewStopped();
			}
			break;
		}
		;
	}

	private void processExitState(int state) {
		switch (state) {
		case STARTED:
			onExitStartedState();
			break;
		case STOPPED:
			onExitStoppedState();
			break;
		}
		;
	}

	private void onEnterStoppedState() {
		/* nothing to do */
	}

	private void onExitStoppedState() {
		/* nothing to do */
	}

	// NOTE: The order of bitmap constructor and camera connection is important
	// for android 4.1.x
	// Bitmap must be constructed before surface
	private void onEnterStartedState() {
		/* Connect camera */
		if (!connectCamera(getWidth(), getHeight())) {
			AlertDialog ad = new AlertDialog.Builder(getContext()).create();
			ad.setCancelable(false); // This blocks the 'BACK' button
			ad.setMessage("It seems that you device does not support camera (or it is locked). Application will be closed.");
			ad.setButton(DialogInterface.BUTTON_NEUTRAL, "OK",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							((Activity) getContext()).finish();
						}
					});
			ad.show();

		}
	}

	private void onExitStartedState() {
		disconnectCamera();
		if (mCacheBitmap != null) {
			mCacheBitmap.recycle();
		}
	}

	/**
	 * This method shall be called by the subclasses when they have valid object
	 * and want it to be delivered to external client (via callback) and then
	 * displayed on the screen.
	 * 
	 * @param frame
	 *            - the current frame to be delivered
	 */
	protected void deliverAndDrawFrame(CvCameraViewFrame frame) {
		Mat modified;

		if (USE_CUSTOM_DETECTION) {
			if (mListener != null) {
				modified = mListener.onCameraFrame(frame);
			} else {
				modified = frame.rgb();
			}
		} else {
			modified = frame.rgb();
		}

		boolean bmpValid = true;
		if (tempRgbMat == null)
			tempRgbMat = new Mat();
		if (modified != null) {
			try {
				if (mCameraIndex == CAMERA_ID_FRONT) {
					Mat flippedMat = new Mat();
					Core.flip(modified, flippedMat, 1);
					Utils.matToBitmap(flippedMat, mCacheBitmap);
					flippedMat.copyTo(tempRgbMat);
					flippedMat.release();
				} else {
					Utils.matToBitmap(modified, mCacheBitmap);
					modified.copyTo(tempRgbMat);
				}
			} catch (Exception e) {
				Log.e(TAG, "Mat type: " + modified);
				Log.e(TAG, "Bitmap type: " + mCacheBitmap.getWidth() + "*"
						+ mCacheBitmap.getHeight());
				Log.e(TAG,
						"Utils.matToBitmap() throws an exception: "
								+ e.getMessage());
				bmpValid = false;
			}
		}
		modified.release();

		if (bmpValid && mCacheBitmap != null) {
			Canvas canvas = getHolder().lockCanvas();
			if (canvas != null) {
				if (false)
					canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
				Log.d(TAG, "mStretch value: " + mScale);

				if (mScale != 0) {
					if (true) {
						canvas.drawBitmap(
								mCacheBitmap,
								new Rect(0, 0, mCacheBitmap.getWidth(),
										mCacheBitmap.getHeight()),
								new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
					} else {
						canvas.drawBitmap(
								mCacheBitmap,
								new Rect(0, 0, mCacheBitmap.getWidth(),
										mCacheBitmap.getHeight()),
								new Rect(
										(int) ((canvas.getWidth() - mScale
												* mCacheBitmap.getWidth()) / 2),
										(int) ((canvas.getHeight() - mScale
												* mCacheBitmap.getHeight()) / 2),
										(int) ((canvas.getWidth() - mScale
												* mCacheBitmap.getWidth()) / 2 + mScale
												* mCacheBitmap.getWidth()),
										(int) ((canvas.getHeight() - mScale
												* mCacheBitmap.getHeight()) / 2 + mScale
												* mCacheBitmap.getHeight())), null);
					}
				} else {
					canvas.drawBitmap(
							mCacheBitmap,
							new Rect(0, 0, mCacheBitmap.getWidth(),
									mCacheBitmap.getHeight()),
							new Rect((canvas.getWidth() - mCacheBitmap
									.getWidth()) / 2,
									(canvas.getHeight() - mCacheBitmap
											.getHeight()) / 2, (canvas
											.getWidth() - mCacheBitmap
											.getWidth())
											/ 2 + mCacheBitmap.getWidth(),
									(canvas.getHeight() - mCacheBitmap
											.getHeight())
											/ 2
											+ mCacheBitmap.getHeight()), null);
				}

				if (!USE_CUSTOM_DETECTION) {
					synchronized (facesRectList) {
						for (int i = 0; i < facesRectList.size(); i++) {
							Rect tempFaceRect = facesRectList.get(i);
							
							canvas.drawRect(new Rect((tempFaceRect.left + 1000) * canvas.getWidth() / 2000,
									(tempFaceRect.top + 1000) * canvas.getHeight() / 2000,
									(tempFaceRect.right + 1000) * canvas.getWidth() / 2000,
									(tempFaceRect.bottom + 1000) * canvas.getHeight() / 2000), mDetectionPaint);
						}
					}
				}
				
				if (mFpsMeter != null) {
					mFpsMeter.measure();
					mFpsMeter.draw(canvas, 20, 30);
				}
				getHolder().unlockCanvasAndPost(canvas);
			}
		}
	}

	// NOTE: On Android 4.1.x the function must be called before SurfaceTextre
	// constructor!
	protected void AllocateCache() {
		mCacheBitmap = Bitmap.createBitmap(mFrameWidth, mFrameHeight,
				Bitmap.Config.RGB_565);
	}

	public interface ListItemAccessor {
		public int getWidth(Object obj);

		public int getHeight(Object obj);
	};

	/**
	 * This helper method can be called by subclasses to select camera preview
	 * size. It goes over the list of the supported preview sizes and selects
	 * the maximum one which fits both values set via setMaxFrameSize() and
	 * surface frame allocated for this view
	 * 
	 * @param supportedSizes
	 * @param surfaceWidth
	 * @param surfaceHeight
	 * @return optimal frame size
	 */
	protected Size calculateCameraFrameSize(List<android.hardware.Camera.Size> supportedSizes,
			int surfaceWidth, int surfaceHeight) {
		int calcWidth = 0;
		int calcHeight = 0;

		int maxAllowedWidth = (mMaxWidth != MAX_UNSPECIFIED && mMaxWidth < surfaceWidth) ? mMaxWidth
				: surfaceWidth;
		int maxAllowedHeight = (mMaxHeight != MAX_UNSPECIFIED && mMaxHeight < surfaceHeight) ? mMaxHeight
				: surfaceHeight;

		for (android.hardware.Camera.Size size : supportedSizes) {
			int width = size.width;
			int height = size.height;

			if (width <= maxAllowedWidth && height <= maxAllowedHeight) {
				if (width >= calcWidth && height >= calcHeight) {
					calcWidth = (int) width;
					calcHeight = (int) height;
				}
			}
		}

		return new Size(calcWidth, calcHeight);
	}

	protected class CvCameraViewListenerAdapter implements
			CvCameraViewListener2 {
		public CvCameraViewListenerAdapter(CvCameraViewListener oldStypeListener) {
			mOldStyleListener = oldStypeListener;
		}

		public void onCameraViewStarted(int width, int height) {
			mOldStyleListener.onCameraViewStarted(width, height);
		}

		public void onCameraViewStopped() {
			mOldStyleListener.onCameraViewStopped();
		}

		public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
			Mat result = null;
			switch (mPreviewFormat) {
			case Highgui.CV_CAP_ANDROID_COLOR_FRAME_RGB:
				result = mOldStyleListener.onCameraFrame(inputFrame.rgb());
				break;
			case Highgui.CV_CAP_ANDROID_GREY_FRAME:
				result = mOldStyleListener.onCameraFrame(inputFrame.gray());
				break;
			default:
				Log.e(TAG,
						"Invalid frame format! Only RGB and Gray Scale are supported!");
			}
			;

			return result;
		}

		public void setFrameFormat(int format) {
			mPreviewFormat = format;
		}

		private int mPreviewFormat = Highgui.CV_CAP_ANDROID_COLOR_FRAME_RGB;
		private CvCameraViewListener mOldStyleListener;
	};

	/**
	 * This class interface is abstract representation of single frame from
	 * camera for onCameraFrame callback Attention: Do not use objects, that
	 * represents this interface out of onCameraFrame callback!
	 */
	public interface CvCameraViewFrame {

		/**
		 * This method returns RGB Mat with frame
		 */
		public Mat rgb();

		/**
		 * This method returns single channel gray scale Mat with frame
		 */
		public Mat gray();
	};

	private class JavaCameraFrame implements CvCameraViewFrame {
		public Mat gray() {
			return mYuvFrameData.submat(0, mHeight, 0, mWidth);
		}

		public Mat rgb() {
			Imgproc.cvtColor(mYuvFrameData, mRgb, Imgproc.COLOR_YUV2RGB_NV21);
			return mRgb;
		}

		public JavaCameraFrame(Mat Yuv420sp, int width, int height) {
			super();
			mWidth = width;
			mHeight = height;
			mYuvFrameData = Yuv420sp;
			mRgb = new Mat();
		}

		public void release() {
			mRgb.release();
		}

		private Mat mYuvFrameData;
		private Mat mRgb;
		private int mWidth;
		private int mHeight;
	};

	private class CameraWorker implements Runnable {

		public void run() {
			do {
				synchronized (CameraPreview.this) {
					try {
						CameraPreview.this.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				if (!mStopThread) {
					if (!mFrameChain[mChainIdx].empty())
						deliverAndDrawFrame(mCameraFrame[mChainIdx]);
					mChainIdx = 1 - mChainIdx;
				}
			} while (!mStopThread);
			Log.d(TAG, "Finish processing thread");
		}
	}
	
	public void setDetectionActivity(FdActivity activity) {
		detectionActivity = activity;
	}
	
	public Mat getTempRgbMat() {
		return tempRgbMat;
	}
	
	@Override
	public void onFaceDetection(Face[] faces, Camera camera) {
		synchronized (facesRectList) {
			facesRectList.clear();
			if (faces.length > 0) {
				for (int i = 0 ; i < faces.length ; i ++) {
					Rect tempRect = faces[i].rect;
					if (mCameraIndex == CAMERA_ID_FRONT) {
						tempRect = new Rect(-tempRect.right, tempRect.top, -tempRect.left, tempRect.bottom);
					}
					facesRectList.add(tempRect);
				}
			}
				
			if (detectionActivity != null) {
				org.opencv.core.Rect[] faceRectArray = new org.opencv.core.Rect[facesRectList.size()];
				for (int i = 0 ; i < facesRectList.size() ; i ++) {
					Rect tempFaceRect = facesRectList.get(i);
					int rotation = (detectionActivity.getOrientation() + 3) % 4;;
					if (rotation == Surface.ROTATION_0) {
						faceRectArray[i] = new org.opencv.core.Rect(tempFaceRect.left, tempFaceRect.top, tempFaceRect.width(), tempFaceRect.height());
					} else if (rotation == Surface.ROTATION_90) {
						faceRectArray[i] = new org.opencv.core.Rect(tempFaceRect.top, -tempFaceRect.right, tempFaceRect.height(), tempFaceRect.width());
					} else if (rotation == Surface.ROTATION_180) {
						faceRectArray[i] = new org.opencv.core.Rect(-tempFaceRect.right, -tempFaceRect.bottom, tempFaceRect.width(), tempFaceRect.height());
					} else if (rotation == Surface.ROTATION_270) {
						faceRectArray[i] = new org.opencv.core.Rect(-tempFaceRect.bottom, tempFaceRect.left, tempFaceRect.height(), tempFaceRect.width());
					} 
				}
				detectionActivity.determineCenterPosition(faceRectArray, 0, 0, 200, 150);
				faceRectArray = null;
			}
		}
	}
}
