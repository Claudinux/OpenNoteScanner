package com.todobom.opennotescanner;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.shapes.PathShape;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.todobom.opennotescanner.helpers.OpenNoteMessage;
import com.todobom.opennotescanner.helpers.PreviewFrame;
import com.todobom.opennotescanner.helpers.Quadrilateral;
import com.todobom.opennotescanner.helpers.ScannedDocument;
import com.todobom.opennotescanner.helpers.Utils;
import com.todobom.opennotescanner.views.HUDCanvasView;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;

/**
 * Created by allgood on 05/03/16.
 */
public class ImageProcessor extends Handler {

    private static final String TAG = "ImageProcessor";
    private final Handler mUiHandler;
    private final OpenNoteScannerActivity mMainActivity;
    private boolean colorMode=false;
    private double colorGain = 1.5;       // contrast
    private double colorBias = 0;         // bright
    private int colorThresh = 150;        // threshold
    private Size previewSize;
    private Point[] previewPoints;


    public ImageProcessor ( Looper looper , Handler uiHandler , OpenNoteScannerActivity mainActivity ) {
        super(looper);
        mUiHandler = uiHandler;
        mMainActivity = mainActivity;

    }

    public void handleMessage ( Message msg ) {

        if (msg.obj.getClass() == OpenNoteMessage.class) {

            OpenNoteMessage obj = (OpenNoteMessage) msg.obj;

            String command = obj.getCommand();

            Log.d(TAG, "Message Received: " + command + " - " + obj.getObj().toString() );

            if ( command.equals("previewFrame")) {
                processPreviewFrame((PreviewFrame) obj.getObj());
            } else if ( command.equals("pictureTaken")) {
                processPicture((Mat) obj.getObj());
            } else if ( command.equals("colorMode")) {
                colorMode=(Boolean) obj.getObj();
            }
        }
    }

    private void processPreviewFrame( PreviewFrame previewFrame ) {


        Result[] results = {};

        Mat frame = previewFrame.getFrame();

        try {
            results = zxing(frame);
        } catch (ChecksumException | FormatException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        boolean qrOk = false;
        String currentQR=null;

        for (Result result: results) {
            String qrText = result.getText();
            if ( Utils.isMatch(qrText, "^P.. V.. S[0-9]+") && checkQR(qrText)) {
                Log.d(TAG, "QR Code valid: " + result.getText());
                qrOk = true;
                currentQR = qrText;
                break;
            } else {
                Log.d(TAG, "QR Code ignored: " + result.getText());
            }
        }


        int width = frame.width();
        int height = frame.height();

        for (Result result : results) {
            ResultPoint[] rp = result.getResultPoints();

            Point lpi = null;

            for (int i = 0; i < rp.length; i += 1) {
                Point pi = new Point();
                pi.y = rp[i].getY();
                pi.x = rp[i].getX() + width/2 + height/4;

                if (lpi != null) {
                    // disabled - TODO: use a canvas on UI thread
                    // Imgproc.line(previewFrame, lpi, pi, new Scalar(255, 0, 0), 10);
                }
                lpi = pi;
            }

        }

        boolean autoMode = previewFrame.isAutoMode();
        boolean previewOnly = previewFrame.isPreviewOnly();

        if ( detectPreviewDocument(frame) && ( (!autoMode && !previewOnly ) || ( autoMode && qrOk ) ) ) {

            mMainActivity.waitSpinnerVisible();

            mMainActivity.requestPicture();

            if (qrOk) {
                pageHistory.put(currentQR, new Date().getTime() / 1000);
                Log.d(TAG, "QR Code scanned: " + currentQR);
            }
        }

        frame.release();
        mMainActivity.setImageProcessorBusy(false);

    }

    public void processPicture( Mat picture ) {

        Mat img = Imgcodecs.imdecode(picture, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
        picture.release();

        Log.d(TAG, "processPicture - imported image " + img.size().width + "x" + img.size().height);

        ScannedDocument doc = detectDocument(img);
        mMainActivity.saveDocument(doc);

        doc.release();
        picture.release();

        mMainActivity.setImageProcessorBusy(false);
        mMainActivity.waitSpinnerInvisible();
    }


    private ScannedDocument detectDocument(Mat inputRgba) {
        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        ScannedDocument sd = new ScannedDocument(inputRgba);

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        Mat doc;

        if (quad != null) {

            MatOfPoint c = quad.contour;

            sd.quadrilateral = quad;
            sd.previewPoints = previewPoints;
            sd.previewSize = previewSize;

            doc = fourPointTransform(inputRgba, quad.points);

        } else {
            doc = new Mat( inputRgba.size() , CvType.CV_8UC4 );
            inputRgba.copyTo(doc);
        }

        enhanceDocument(doc);
        return sd.setProcessed(doc);
    }


    private HashMap<String,Long> pageHistory = new HashMap<>();

    private boolean checkQR(String qrCode) {

        return ! ( pageHistory.containsKey(qrCode) &&
                pageHistory.get(qrCode) > new Date().getTime()/1000-15) ;

    }

    private boolean detectPreviewDocument(Mat inputRgba) {

        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        previewPoints = null;
        previewSize = inputRgba.size();

        if (quad != null) {

            Point[] rescaledPoints = new Point[4];

            double ratio = inputRgba.size().height / 500;

            for ( int i=0; i<4 ; i++ ) {
                int x = Double.valueOf(quad.points[i].x*ratio).intValue();
                int y = Double.valueOf(quad.points[i].y*ratio).intValue();
                rescaledPoints[i] = new Point(x,y);
            }

            previewPoints = rescaledPoints;

            drawDocumentBox(previewPoints , previewSize);

            Log.d(TAG, quad.points[0].toString() + " , " + quad.points[1].toString() + " , " + quad.points[2].toString() + " , " + quad.points[3].toString());

            return true;

        }

        mMainActivity.getHUD().clear();
        mMainActivity.invalidateHUD();

        return false;

    }

    private void drawDocumentBox(Point[] points, Size stdSize) {

        Path path = new Path();

        HUDCanvasView hud = mMainActivity.getHUD();

        // ATTENTION: axis are swapped

        float previewWidth = (float) stdSize.height;
        float previewHeight = (float) stdSize.width;

        path.moveTo( previewWidth - (float) points[0].y, (float) points[0].x );
        path.lineTo( previewWidth - (float) points[1].y, (float) points[1].x );
        path.lineTo( previewWidth - (float) points[2].y, (float) points[2].x );
        path.lineTo( previewWidth - (float) points[3].y, (float) points[3].x );
        path.close();

        PathShape newBox = new PathShape(path , previewWidth , previewHeight);

        Paint paint = new Paint();
        paint.setColor(Color.argb(64, 0, 255, 0));

        Paint border = new Paint();
        border.setColor(Color.rgb(0, 255, 0));
        border.setStrokeWidth(5);

        hud.clear();
        hud.addShape(newBox, paint, border);
        mMainActivity.invalidateHUD();


    }

    private Quadrilateral getQuadrilateral( ArrayList<MatOfPoint> contours , Size srcSize ) {

        double ratio = srcSize.height / 500;
        int height = Double.valueOf(srcSize.height / ratio).intValue();
        int width = Double.valueOf(srcSize.width / ratio).intValue();
        Size size = new Size(width,height);

        for ( MatOfPoint c: contours ) {
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

            Point[] points = approx.toArray();

            // select biggest 4 angles polygon
            if (points.length == 4) {
                Point[] foundPoints = sortPoints(points);

                if (insideArea(foundPoints, size)) {
                    return new Quadrilateral( c , foundPoints );
                }
            }
        }

        return null;
    }

    private Point[] sortPoints( Point[] src ) {

        ArrayList<Point> srcPoints = new ArrayList<>(Arrays.asList(src));

        Point[] result = { null , null , null , null };

        Comparator<Point> sumComparator = new Comparator<Point>() {
            @Override
            public int compare(Point lhs, Point rhs) {
                return Double.valueOf(lhs.y + lhs.x).compareTo(rhs.y + rhs.x);
            }
        };

        Comparator<Point> diffComparator = new Comparator<Point>() {

            @Override
            public int compare(Point lhs, Point rhs) {
                return Double.valueOf(lhs.y - lhs.x).compareTo(rhs.y - rhs.x);
            }
        };

        // top-left corner = minimal sum
        result[0] = Collections.min(srcPoints, sumComparator);

        // bottom-right corner = maximal sum
        result[2] = Collections.max(srcPoints, sumComparator);

        // top-right corner = minimal diference
        result[1] = Collections.min(srcPoints, diffComparator);

        // bottom-left corner = maximal diference
        result[3] = Collections.max(srcPoints, diffComparator);

        return result;
    }

    private boolean insideArea(Point[] rp, Size size) {

        int width = Double.valueOf(size.width).intValue();
        int height = Double.valueOf(size.height).intValue();
        int baseMeasure = height/4;

        int bottomPos = height-baseMeasure;
        int topPos = baseMeasure;
        int leftPos = width/2-baseMeasure;
        int rightPos = width/2+baseMeasure;

        return (
                rp[0].x <= leftPos && rp[0].y <= topPos
                        && rp[1].x >= rightPos && rp[1].y <= topPos
                        && rp[2].x >= rightPos && rp[2].y >= bottomPos
                        && rp[3].x <= leftPos && rp[3].y >= bottomPos

        );
    }

    private void enhanceDocument( Mat src ) {
        if (colorMode) {
            src.convertTo(src,-1, colorGain , colorBias);
            Imgproc.threshold(src, src, colorThresh, 255, Imgproc.THRESH_BINARY);
        } else {
            Imgproc.cvtColor(src,src,Imgproc.COLOR_RGBA2GRAY);
            Imgproc.adaptiveThreshold(src,src,255,Imgproc.ADAPTIVE_THRESH_MEAN_C,Imgproc.THRESH_BINARY,15,15);
        }
    }

    private Mat fourPointTransform( Mat src , Point[] pts ) {

        double ratio = src.size().height / 500;
        int height = Double.valueOf(src.size().height / ratio).intValue();
        int width = Double.valueOf(src.size().width / ratio).intValue();

        Point tl = pts[0];
        Point tr = pts[1];
        Point br = pts[2];
        Point bl = pts[3];

        double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
        double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

        double dw = Math.max(widthA, widthB)*ratio;
        int maxWidth = Double.valueOf(dw).intValue();


        double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
        double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

        double dh = Math.max(heightA, heightB)*ratio;
        int maxHeight = Double.valueOf(dh).intValue();

        Mat doc = new Mat(maxHeight, maxWidth, CvType.CV_8UC4);

        Mat src_mat = new Mat(4, 1, CvType.CV_32FC2);
        Mat dst_mat = new Mat(4, 1, CvType.CV_32FC2);

        src_mat.put(0, 0, tl.x*ratio, tl.y*ratio, tr.x*ratio, tr.y*ratio, br.x*ratio, br.y*ratio, bl.x*ratio, bl.y*ratio);
        dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

        Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

        Imgproc.warpPerspective(src, doc, m, doc.size());

        return doc;
    }

    private ArrayList<MatOfPoint> findContours(Mat src) {

        Mat grayImage = null;
        Mat cannedImage = null;
        Mat resizedImage = null;

        double ratio = src.size().height / 500;
        int height = Double.valueOf(src.size().height / ratio).intValue();
        int width = Double.valueOf(src.size().width / ratio).intValue();
        Size size = new Size(width,height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC4);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src,resizedImage,size);
        Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
        Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
        Imgproc.Canny(grayImage, cannedImage, 75, 200);

        ArrayList<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(cannedImage, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        hierarchy.release();

        Collections.sort(contours, new Comparator<MatOfPoint>() {

            @Override
            public int compare(MatOfPoint lhs, MatOfPoint rhs) {
                return Double.valueOf(Imgproc.contourArea(rhs)).compareTo(Imgproc.contourArea(lhs));
            }
        });

        resizedImage.release();
        grayImage.release();
        cannedImage.release();

        return contours;
    }

    private QRCodeMultiReader qrCodeMultiReader = new QRCodeMultiReader();



    public Result[] zxing( Mat inputImage ) throws ChecksumException, FormatException {

        int w = inputImage.width();
        int h = inputImage.height();

        Mat southEast = inputImage.submat(0, h/4, w/2 + h/4, w);

        Bitmap bMap = Bitmap.createBitmap(southEast.width(), southEast.height(), Bitmap.Config.ARGB_8888);
        org.opencv.android.Utils.matToBitmap(southEast, bMap);
        southEast.release();
        int[] intArray = new int[bMap.getWidth()*bMap.getHeight()];
        //copy pixel data from the Bitmap into the 'intArray' array
        bMap.getPixels(intArray, 0, bMap.getWidth(), 0, 0, bMap.getWidth(), bMap.getHeight());

        LuminanceSource source = new RGBLuminanceSource(bMap.getWidth(), bMap.getHeight(),intArray);

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Result result = null;
        ResultPoint[] rps = null;

        Result[] results = {};
        try {
            results = qrCodeMultiReader.decodeMultiple(bitmap);
        }
        catch (NotFoundException e) {
        }

        return results;

    }

}
