package com.psjdc.mobilefacedetection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.ImageView;

import com.psjdc.mobilefacedetection.CameraPreview.CvCameraViewFrame;
import com.psjdc.mobilefacedetection.CameraPreview.CvCameraViewListener2;

public class FdActivity extends Activity implements CvCameraViewListener2,
		OnTouchListener, OnClickListener {

	private static final String TAG = "OCVSample::Activity";
	private static final Scalar FACE_RECT_COLOR = new Scalar(0, 255, 0, 255);

	private ImageView cameraImage;
	
	private Mat mRgbMat;
	private Mat mGrayMat;
	private ArrayList<Rect> mDetecionRectList = new ArrayList<Rect>();

	private File mCascadeFile;
	private CascadeClassifier mJavaDetector;
	private DetectionBasedTracker mNativeDetector;

	private float mRelativeFaceSize = 0.2f;
	private int mAbsoluteFaceSize = 0;

	private CameraPreview mCameraView;

	private Vibrator mVibrator;
	private MediaPlayer mPlayer;

	private final int deltaX = 20;
	private final int deltaY = 20;

	private final int POSITION_LEFT = 0;
	private final int POSITION_RIGHT = 1;
	private final int POSITION_TOP = 2;
	private final int POSITION_BOTTOM = 3;
	private final int POSITION_CENTER = 4;
	private final int POSITION_NONE = -1;
	private int mPosition = POSITION_NONE;

	private WindowOrientationListener mOrientationListener;
	private int mOrientation = -1;

	private Thread mThread;
	private boolean mStopThread;

	private boolean mLogEnable = false;
	private boolean mUseJavaDetector = true;

	public static final String TAKEN_PICTURE = "TakenPicture";

	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				Log.i(TAG, "OpenCV loaded successfully");

				System.loadLibrary("detection_based_tracker");
				mCameraView.enableView();
				try {
					// load cascade file from application resources
					InputStream is = getResources().openRawResource(
							R.raw.haarcascade_frontalface_alt);
					File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
					mCascadeFile = new File(cascadeDir,
							"haarcascade_frontalface_alt.xml");
					FileOutputStream os = new FileOutputStream(mCascadeFile);

					byte[] buffer = new byte[4096];
					int bytesRead;
					while ((bytesRead = is.read(buffer)) != -1) {
						os.write(buffer, 0, bytesRead);
					}
					is.close();
					os.close();

					if (mUseJavaDetector) {
						mJavaDetector = new CascadeClassifier(
								mCascadeFile.getAbsolutePath());
						if (mJavaDetector.empty()) {
							Log.e(TAG, "Failed to load cascade classifier");
							mJavaDetector = null;
						} else
							Log.i(TAG, "Loaded cascade classifier from "
									+ mCascadeFile.getAbsolutePath());
					} else {
						mNativeDetector = new DetectionBasedTracker(
								mCascadeFile.getAbsolutePath(), 0);
						if (mNativeDetector != null)
							mNativeDetector.start();
					}

					cascadeDir.delete();

				} catch (IOException e) {
					e.printStackTrace();
					Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
				}
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	public FdActivity() {
		Log.i(TAG, "Instantiated new " + this.getClass());
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, "called onCreate");
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		initLayout(R.layout.fd_view);

		mVibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
		mPlayer = new MediaPlayer();

		mOrientationListener = new WindowOrientationListener(this) {
			public void onProposedRotationChanged(int rotation) {
				mOrientation = getProposedRotation();
			}
		};
		mDetecionRectList.clear();
		
		cameraImage = (ImageView) findViewById(R.id.img_camera);
		cameraImage.setOnClickListener(this);

		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9, this,
				mLoaderCallback);
	}

	private void initLayout(int layoutId) {
		setContentView(layoutId);

		mCameraView = (CameraPreview) findViewById(R.id.fd_preview);
		if (CameraPreview.USE_CUSTOM_DETECTION)
			mCameraView.setCvCameraViewListener(this);
		mCameraView.setOnTouchListener(this);

		mPosition = POSITION_NONE;
	}

	public void onDestroy() {
		super.onDestroy();
		mOrientationListener = null;

		if (mNativeDetector != null) {
			mNativeDetector.stop();
			mNativeDetector.release();
		}

		mCameraView.disableView();

		if (mPlayer != null) {
			mPlayer.release();
			mPlayer = null;
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (mOrientationListener != null)
			mOrientationListener.disable();

		mCameraView.setDetectionActivity(null);
		
		if (CameraPreview.USE_CUSTOM_DETECTION) {
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
		}
		
		playEffect(-1, false);
	}

	@Override
	public void onResume() {
		super.onResume();
		mOrientationListener.enable();

		mDetecionRectList.clear();

		mCameraView.setDetectionActivity(this);
		
		if (CameraPreview.USE_CUSTOM_DETECTION) {
			mStopThread = false;
			mThread = new Thread(new DetectionWorker());
			mThread.start();
		}

		mPosition = POSITION_NONE;

		System.gc();
	}
	
	public int getOrientation() {
		return mOrientation;
	}

	public void onCameraViewStarted(int width, int height) {
		mRgbMat = new Mat();
		mGrayMat = new Mat();

		mPosition = POSITION_NONE;
	}

	public void onCameraViewStopped() {
		mRgbMat.release();
		mGrayMat.release();

		mPosition = POSITION_NONE;
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		mRgbMat = inputFrame.rgb();
		mGrayMat = inputFrame.gray();

		for (int i = 0; i < mDetecionRectList.size(); i++) {
			Core.rectangle(mRgbMat, mDetecionRectList.get(i).tl(),
					mDetecionRectList.get(i).br(), FACE_RECT_COLOR, 2);
		}

		return mRgbMat;
	}

	public static final int DETECT_WIDTH = 160;
	public static final int DETECT_HEIGHT = 120;

	public void getAffineMat(int rotation, int cameraKind, int cols, int rows,
			ArrayList<Object> matList) {
		// 방향에 맞게 화상을 돌려서 인식시킨다.
		Point viewTri[] = new Point[3]; // screen coordinate position
		Point srcTri[] = new Point[3]; // small image coordinate position
		Point dstTri[] = new Point[3]; // rotated image coordinate postion

		viewTri[0] = new Point(0, 0);
		viewTri[1] = new Point(mGrayMat.size().width - 1, 0);
		viewTri[2] = new Point(0, mGrayMat.size().height - 1);

		srcTri[0] = new Point(0, 0);
		srcTri[1] = new Point(cols - 1, 0);
		srcTri[2] = new Point(0, rows - 1);

		Size affineSize = new Size(cols, rows);
		if (rotation == Surface.ROTATION_0) {
			// 0 degree
			dstTri[0] = new Point(0, 0);
			dstTri[1] = new Point(cols - 1, 0);
			dstTri[2] = new Point(0, rows - 1);
		} else if (rotation == Surface.ROTATION_90
				&& cameraKind == CameraPreview.CAMERA_ID_BACK) {
			affineSize = new Size(rows, cols);
			// -90 degree
			dstTri[0] = new Point(0, cols - 1);
			dstTri[1] = new Point(0, 0);
			dstTri[2] = new Point(rows - 1, cols - 1);
		} else if (rotation == Surface.ROTATION_90
				&& cameraKind == CameraPreview.CAMERA_ID_FRONT) {
			affineSize = new Size(rows, cols);
			// 90 degree
			dstTri[0] = new Point(rows - 1, 0);
			dstTri[1] = new Point(rows - 1, cols - 1);
			dstTri[2] = new Point(0, 0);
		} else if (rotation == Surface.ROTATION_180) {
			// 180 degree
			dstTri[0] = new Point(cols - 1, rows - 1);
			dstTri[1] = new Point(0, rows - 1);
			dstTri[2] = new Point(cols - 1, 0);
		} else if (rotation == Surface.ROTATION_270
				&& cameraKind == CameraPreview.CAMERA_ID_BACK) {
			affineSize = new Size(rows, cols);
			// 90 degree
			dstTri[0] = new Point(rows - 1, 0);
			dstTri[1] = new Point(rows - 1, cols - 1);
			dstTri[2] = new Point(0, 0);
		} else if (rotation == Surface.ROTATION_270
				&& cameraKind == CameraPreview.CAMERA_ID_FRONT) {
			affineSize = new Size(rows, cols);
			// -90 degree
			dstTri[0] = new Point(0, cols - 1);
			dstTri[1] = new Point(0, 0);
			dstTri[2] = new Point(rows - 1, cols - 1);
		} else {
			// 0 degree
			dstTri[0] = new Point(0, 0);
			dstTri[1] = new Point(cols - 1, 0);
			dstTri[2] = new Point(0, rows - 1);
		}

		Mat affineMat = Imgproc.getAffineTransform(new MatOfPoint2f(srcTri),
				new MatOfPoint2f(dstTri));
		Mat invertMat = Imgproc.getAffineTransform(new MatOfPoint2f(dstTri),
				new MatOfPoint2f(viewTri));

		matList.add(affineMat);
		matList.add(invertMat);
		matList.add(affineSize);
	}

	public void displayDetection() {
		try {
			if (mGrayMat == null || mGrayMat.empty())
				return;

			Mat smallImg = new Mat();
			Mat rotImg = new Mat();

			int rotation = (mOrientation + 3) % 4;

			Imgproc.resize(mGrayMat, smallImg, new Size(DETECT_WIDTH,
					DETECT_HEIGHT));

			int cameraKind = CameraPreview.CAMERA_ID_BACK;
			if (mCameraView != null)
				cameraKind = mCameraView.getCameraIndex();

			ArrayList<Object> matList = new ArrayList<Object>();
			getAffineMat(rotation, cameraKind, smallImg.cols(),
					smallImg.rows(), matList);

			Imgproc.warpAffine(smallImg, rotImg, (Mat) matList.get(0),
					(Size) matList.get(2));

			if (mAbsoluteFaceSize == 0) {
				int height = Math.min(rotImg.cols(), rotImg.rows());
				if (Math.round(height * mRelativeFaceSize) > 0) {
					mAbsoluteFaceSize = Math.round(height * mRelativeFaceSize);
				}
				if (mNativeDetector != null)
					mNativeDetector.setMinFaceSize(mAbsoluteFaceSize);
			}

			MatOfRect faces = new MatOfRect();

			if (mUseJavaDetector) {
				if (mJavaDetector != null)
					mJavaDetector.detectMultiScale(rotImg, faces, 1.1,
							2,
							2, // TODO:
								// objdetect.CV_HAAR_SCALE_IMAGE
							new Size(mAbsoluteFaceSize, mAbsoluteFaceSize),
							new Size(mAbsoluteFaceSize * 4, mAbsoluteFaceSize * 4));
			} else if (mNativeDetector != null)
				mNativeDetector.detect(rotImg, faces);

			Rect[] facesArray = faces.toArray();
			if (false) {
				ArrayList<Rect> facesList = new ArrayList<Rect>();
//			 크기순서로 반대로 정렬한다.
				for (int i = 0; i < facesArray.length; i++) {
					int j = 0;
					for (; j < facesList.size(); j++) {
						Rect currentRect = facesList.get(j);
						if (facesArray[i].width * facesArray[i].height > currentRect.width
								* currentRect.height)
							break;
					}
					facesList.add(j, facesArray[i]);
				}
			}

			Point ptx, pt1 = new Point(), pt2 = new Point();
			mDetecionRectList.clear();
			Mat invertMat = (Mat) matList.get(1);
			for (int i = 0; i < facesArray.length; i++) {
				ptx = facesArray[i].tl();
				pt1.x = ptx.x * invertMat.get(0, 0)[0] + ptx.y
						* invertMat.get(0, 1)[0] + invertMat.get(0, 2)[0];
				pt1.y = ptx.x * invertMat.get(1, 0)[0] + ptx.y
						* invertMat.get(1, 1)[0] + invertMat.get(1, 2)[0];

				ptx = facesArray[i].br();
				pt2.x = ptx.x * invertMat.get(0, 0)[0] + ptx.y
						* invertMat.get(0, 1)[0] + invertMat.get(0, 2)[0];
				pt2.y = ptx.x * invertMat.get(1, 0)[0] + ptx.y
						* invertMat.get(1, 1)[0] + invertMat.get(1, 2)[0];

				mDetecionRectList.add(new Rect(pt1, pt2));
			}
			
			determineCenterPosition(facesArray, rotImg.cols() / 2, rotImg.rows() / 2, deltaX, deltaY);

			faces.release();
			smallImg.release();
			rotImg.release();
		} catch (Exception e) {
			mPosition = POSITION_NONE;
		} finally {
//			 System.gc();
		}
	}
	
	public void determineCenterPosition(Rect[] facesArray, int centerX, int centerY, int minDeltaX, int minDeltaY) {
		if (facesArray.length > 0) {
			int total_x = 0;
			int total_y = 0;
			for (int i = 0; i < facesArray.length; i++) {
				Rect tempRect = facesArray[i];
				int center_x = tempRect.x + tempRect.width
						/ 2;
				int center_y = tempRect.y + tempRect.height
						/ 2;

				total_x += center_x;
				total_y += center_y;
			}
			total_x /= facesArray.length;
			total_y /= facesArray.length;
			if (total_x < (centerX - minDeltaX)) {
				if (mPosition != POSITION_LEFT) {
					playEffect(R.raw.left, false);
					if (mLogEnable)
						Log.e("face detection warning",
								"=============left================");
					mPosition = POSITION_LEFT;
				}
			} else if (total_x > (centerX + minDeltaX)) {
				if (mPosition != POSITION_RIGHT) {
					playEffect(R.raw.right, false);
					if (mLogEnable)
						Log.e("face detection warning",
								"=============right================");
					mPosition = POSITION_RIGHT;
				}
			} else if (total_y < (centerY - minDeltaY)) {
				if (mPosition != POSITION_TOP) {
					playEffect(R.raw.up, false);
					if (mLogEnable)
						Log.e("face detection warning",
								"=============up================");
					mPosition = POSITION_TOP;
				}
			} else if (total_y > (centerY + minDeltaY)) {
				if (mPosition != POSITION_BOTTOM) {
					playEffect(R.raw.down, false);
					if (mLogEnable)
						Log.e("face detection warning",
								"=============down================");
					mPosition = POSITION_BOTTOM;
				}
			} else {
				// 이미 기억되여 있으면 그것을 되돌린다.
				if (mPosition != POSITION_CENTER) {
					playEffect(R.raw.center, true);
					if (mLogEnable)
						Log.e("face detection warning",
								"=============center================");
					mPosition = POSITION_CENTER;
				}
			}
		} else {
			playEffect(-1, false);
			mPosition = POSITION_NONE;
		}
	}

	private class DetectionWorker implements Runnable {
		public void run() {
			do {
				if (!mStopThread) {
					displayDetection();
				}
			} while (!mStopThread);
			Log.d(TAG, "Finish processing thread");
		}
	}

	public static final String packageName = "com.psjdc.mobilefacedetection";
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		if (mPosition == POSITION_CENTER) {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
			String currentDateandTime = sdf.format(new Date());
			String fileName = Environment.getDataDirectory()
					.getAbsolutePath() + "/data/" + packageName
					+ "/sample_picture_"
					+ currentDateandTime
					+ ".jpg";

			mCameraView.setDetectionActivity(null);
			
			takePicture(fileName, mCameraView.getTempRgbMat());

//			Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT)
//					.show();
			mPosition = POSITION_NONE;
		}

		return false;
	}

	public void takePicture(String fileName, Mat rgbMat) {
		int rotation = (mOrientation + 3) % 4;

		int minWidth = Math.min(rgbMat.rows(), rgbMat.cols());
		int maxWidth = Math.max(rgbMat.rows(), rgbMat.cols());

		Point min_center = new Point(minWidth / 2, minWidth / 2);
		Point landscape_center = new Point(rgbMat.cols() / 2,
				rgbMat.rows() / 2);
		Point max_center = new Point(maxWidth / 2, maxWidth / 2);
		Mat min_rotate_270 = Imgproc.getRotationMatrix2D(min_center, 270, 1);
		Mat landscape_rotate_180 = Imgproc.getRotationMatrix2D(
				landscape_center, 180, 1);
		Mat max_rotate_90 = Imgproc.getRotationMatrix2D(max_center, 90, 1);

		Size landscapeSize = rgbMat.size();
		Size portraitSize = new Size(rgbMat.rows(), rgbMat.cols());

		final Mat saveMat = new Mat();
		// 방향에 맞게 화상을 돌려서 보관시킨다.
		if (rotation == Surface.ROTATION_0) {
			rgbMat.copyTo(saveMat);
		} else if (rotation == Surface.ROTATION_90) {
			Imgproc.warpAffine(rgbMat, saveMat, max_rotate_90,
					portraitSize);
		} else if (rotation == Surface.ROTATION_180) {
			Imgproc.warpAffine(rgbMat, saveMat, landscape_rotate_180,
					landscapeSize);
		} else if (rotation == Surface.ROTATION_270) {
			Imgproc.warpAffine(rgbMat, saveMat, min_rotate_270,
					portraitSize);
		}
		Mat cropMat = new Mat();
		Mat thumbnailMat = new Mat();
		int saveMinWidth = Math.min(saveMat.rows(), saveMat.cols());
		Imgproc.getRectSubPix(saveMat, new Size(saveMinWidth, saveMinWidth), new Point(saveMat.cols() / 2, saveMat.rows() / 2), cropMat);
		Imgproc.resize(cropMat, thumbnailMat, new Size(150, 150));

		Bitmap thumbnail = Bitmap.createBitmap(thumbnailMat.cols(), thumbnailMat.rows(), Bitmap.Config.ARGB_8888);
		Utils.matToBitmap(thumbnailMat, thumbnail);
		thumbnailMat.release();
		cropMat.release();
		
		startShareActivity(thumbnail);
		
		final String saveFilename = fileName;
		Thread saveThread = new Thread() {
			public void run() {
				try{
					Bitmap picture = Bitmap.createBitmap(saveMat.cols(), saveMat.rows(), Bitmap.Config.RGB_565);
					Utils.matToBitmap(saveMat, picture);
					saveMat.release();

					FileOutputStream out = new FileOutputStream(saveFilename);
					picture.compress(Bitmap.CompressFormat.JPEG, 90, out);
					picture.recycle();
					
				} catch(Exception e) {
					e.printStackTrace();
				}
				
			}
		};
		saveThread.run();
	}
	
	public void playEffect(int resId, boolean withVibrator) {
		if (mVibrator == null)
			mVibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
		if (withVibrator) {
			mVibrator.vibrate(new long[] { 100, 50, 200, 50 }, 0);
		} else {
			mVibrator.cancel();
		}
		if (mPlayer == null)
			mPlayer = new MediaPlayer();
		mPlayer.reset();
		try {
			if (resId != -1) {
				AssetFileDescriptor afd = getResources().openRawResourceFd(
						resId);
				mPlayer.setDataSource(afd.getFileDescriptor(),
						afd.getStartOffset(), afd.getLength());
				afd.close();
				mPlayer.prepare();
				mPlayer.start();
			}
		} catch (Exception ex) {
			Log.d("TEST MEDIA PLAYER", "create failed:", ex);
			// fall through
		}
	}

	public void startShareActivity(Bitmap bitmap) {
		Intent intent = new Intent(FdActivity.this, FdImageShareActivity.class);
		if (intent != null) {
			intent.putExtra(TAKEN_PICTURE, bitmap);
			startActivity(intent);
		}
	}

	@Override
	public void onClick(View v) {
		mCameraView.disableView();
		if (mCameraView.getCameraIndex() == CameraPreview.CAMERA_ID_BACK)
			mCameraView.setCameraIndex(CameraPreview.CAMERA_ID_FRONT);
		else
			mCameraView.setCameraIndex(CameraPreview.CAMERA_ID_BACK);
		mCameraView.enableView();
	}
}
