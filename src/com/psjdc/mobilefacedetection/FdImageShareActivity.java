package com.psjdc.mobilefacedetection;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class FdImageShareActivity extends Activity implements OnClickListener{

	// *******************************************************
	// Initialize
	// *******************************************************
	Button shareBtn;
	ImageView imgPreview;
	ImageView imgPreviewScaled;
	LinearLayout facebookLayout;
	LinearLayout twitterLayout;
	LinearLayout instagramLayout;
	LinearLayout emailshareLayout;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.share);

		facebookLayout = (LinearLayout) findViewById(R.id.layout_facebook);
		facebookLayout.setOnClickListener(this);

		twitterLayout = (LinearLayout) findViewById(R.id.layout_twitter);
		twitterLayout.setOnClickListener(this);

		instagramLayout = (LinearLayout) findViewById(R.id.layout_instagram);
		instagramLayout.setOnClickListener(this);
		
		emailshareLayout = (LinearLayout) findViewById(R.id.layout_emailshare);
		emailshareLayout.setOnClickListener(this);
		
		shareBtn = (Button) findViewById(R.id.btn_share);
		shareBtn.setOnClickListener(this);
		
		imgPreview = (ImageView) findViewById(R.id.img_preview);
		imgPreviewScaled = (ImageView) findViewById(R.id.img_preview_scaled);
		
		Bitmap image = (Bitmap)getIntent().getParcelableExtra(FdActivity.TAKEN_PICTURE);
		BitmapDrawable imageDrawable = new BitmapDrawable(image);
		imgPreviewScaled.setBackgroundDrawable(imageDrawable);

		Mat thumbnailMat = Mat.zeros(image.getWidth(), image.getHeight(), CvType.CV_8UC4);
		Utils.bitmapToMat(image, thumbnailMat);
		Mat emptyRoundRectMat = getEmptyRoundRect(thumbnailMat);
		compositeImages(thumbnailMat, emptyRoundRectMat);
		
		Bitmap roundImage = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
		Utils.matToBitmap(thumbnailMat, roundImage);
		BitmapDrawable roundImageDrawable = new BitmapDrawable(roundImage);
		imgPreview.setBackgroundDrawable(roundImageDrawable);

		thumbnailMat.release();
		emptyRoundRectMat.release();

		System.gc();
	}
	
	@Override
	public void onClick(View v) {
		if (v == facebookLayout) {
			facebookLayout.setSelected(!facebookLayout.isSelected());
			twitterLayout.setSelected(false);
			instagramLayout.setSelected(false);
			emailshareLayout.setSelected(false);
		}
		if (v == twitterLayout) {
			twitterLayout.setSelected(!twitterLayout.isSelected());
			facebookLayout.setSelected(false);
			instagramLayout.setSelected(false);
			emailshareLayout.setSelected(false);
		}
		if (v == instagramLayout) {
			instagramLayout.setSelected(!instagramLayout.isSelected());
			facebookLayout.setSelected(false);
			twitterLayout.setSelected(false);
			emailshareLayout.setSelected(false);
		}
		if (v == emailshareLayout) {
			emailshareLayout.setSelected(!emailshareLayout.isSelected());
			facebookLayout.setSelected(false);
			twitterLayout.setSelected(false);
			instagramLayout.setSelected(false);
		}
		if (v == shareBtn) {
			finish();
		}
	}

	private static final int roundness = 15;
	private Mat getEmptyRoundRect(Mat src) {
		int width = src.cols();
		int height = src.rows();
		Scalar white = new Scalar(255, 255, 255, 255);
		Mat emptyMat = Mat.zeros(width, height, src.type());
		Core.rectangle(emptyMat, new Point(roundness, 0), new Point(width - roundness, height), white, -1);
		Core.rectangle(emptyMat, new Point(0, roundness), new Point(width, height - roundness), white, -1);
		Core.circle(emptyMat, new Point(roundness, roundness), roundness, white, -1);
		Core.circle(emptyMat, new Point(width - roundness, roundness), roundness, white, -1);
		Core.circle(emptyMat, new Point(roundness, height - roundness), roundness, white, -1);
		Core.circle(emptyMat, new Point(width - roundness, height - roundness), roundness, white, -1);
		
		return emptyMat;
	}
	
	private void compositeImages(Mat src1, Mat src2) {
		int width = src1.cols();
		int height = src1.rows();
		int r = roundness;
		for(int x = 0; x < r; x ++) {
			for(int y = 0; y < r; y ++) {
				if (x*x+y*y+r*r < 2*(x+y)*r) {
					break;
				}
				src1.put(x, y, new double[4]);
				src1.put(x, height-y, new double[4]);
				src1.put(width-x, y, new double[4]);
				src1.put(width-x, height-y, new double[4]);
			}
		}
	}
}
