package com.nexusid.service;

import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public class CameraBiometricService {
    private static boolean loaded;
    private static final Object CAMERA_LOCK = new Object();
    private static VideoCapture sharedCamera;
    private static int cameraLeases;
    private final CascadeClassifier faceCascade;
    private Net palmNet;
    private List<Point> palmAnchors;
    private Path palmModelPath;
    private volatile boolean palmDetectorReady;
    private Rect lastPalmRect;
    private double lastPalmConfidence;
    private volatile String lastPalmError;

    private static final int PALM_INPUT = 192;
    private static final double PALM_SCORE_THRESHOLD = 0.45;
    private static final double PALM_NMS_THRESHOLD = 0.30;
    private static final String PALM_MODEL_URL =
            "https://github.com/opencv/opencv_zoo/raw/refs/heads/main/models/palm_detection_mediapipe/palm_detection_mediapipe_2023feb.onnx";

    public CameraBiometricService(){
        synchronized(CameraBiometricService.class){
            if(!loaded){ OpenCV.loadLocally(); loaded=true; }
        }
        try(InputStream in=CameraBiometricService.class.getResourceAsStream("/biometrics/haarcascade_frontalface_default.xml")){
            if(in==null) throw new IllegalStateException("Face detector resource is missing.");
            File f=File.createTempFile("nexusid-face-",".xml");
            f.deleteOnExit();
            try(OutputStream out=new FileOutputStream(f)){ in.transferTo(out); }
            faceCascade=new CascadeClassifier(f.getAbsolutePath());
            if(faceCascade.empty()) throw new IllegalStateException("Face detector could not be loaded.");
        }catch(IOException e){ throw new IllegalStateException("Unable to load face detector: "+e.getMessage(),e); }
    }

    public VideoCapture openCamera() throws InterruptedException {
        synchronized(CAMERA_LOCK){
            long deadline=System.currentTimeMillis()+1500L;
            while(cameraLeases>0 && System.currentTimeMillis()<deadline){
                CAMERA_LOCK.wait(50L);
            }
            if(sharedCamera!=null && sharedCamera.isOpened() && cameraLeases==0){
                cameraLeases=1;
                return sharedCamera;
            }
            if(sharedCamera!=null){
                try{sharedCamera.release();}catch(Exception ignored){}
                sharedCamera=null;
                cameraLeases=0;
            }
            VideoCapture opened=new VideoCapture(0,Videoio.CAP_AVFOUNDATION);
            if(!opened.isOpened()){
                try{opened.release();}catch(Exception ignored){}
                opened=new VideoCapture(0);
            }
            if(opened.isOpened()){
                opened.set(Videoio.CAP_PROP_FRAME_WIDTH,640);
                opened.set(Videoio.CAP_PROP_FRAME_HEIGHT,480);
                opened.set(Videoio.CAP_PROP_FPS,30);
                sharedCamera=opened;
                cameraLeases=1;
            }
            return opened;
        }
    }

    public void releaseCameraLease(){
        synchronized(CAMERA_LOCK){
            if(cameraLeases>0) cameraLeases--;
            CAMERA_LOCK.notifyAll();
        }
    }

    public static void shutdownCamera(){
        synchronized(CAMERA_LOCK){
            cameraLeases=0;
            if(sharedCamera!=null){
                try{sharedCamera.release();}catch(Exception ignored){}
                sharedCamera=null;
            }
            CAMERA_LOCK.notifyAll();
        }
    }

    public Mat findFace(Mat frame){
        Mat gray=new Mat();
        Imgproc.cvtColor(frame,gray,Imgproc.COLOR_BGR2GRAY);
        Imgproc.equalizeHist(gray,gray);
        MatOfRect faces=new MatOfRect();
        faceCascade.detectMultiScale(gray,faces,1.05,3,0,new Size(60,60),new Size());
        Rect[] rs=faces.toArray();
        gray.release();
        faces.release();
        if(rs.length==0) return null;
        Rect best=rs[0];
        for(Rect r:rs) if(r.area()>best.area()) best=r;
        int x=Math.max(0,best.x), y=Math.max(0,best.y);
        int w=Math.min(best.width,frame.cols()-x), h=Math.min(best.height,frame.rows()-y);
        if(w<=0||h<=0) return null;
        return new Mat(frame,new Rect(x,y,w,h)).clone();
    }

    public void drawFaceBox(Mat frame){
        Mat gray=new Mat();
        Imgproc.cvtColor(frame,gray,Imgproc.COLOR_BGR2GRAY);
        Imgproc.equalizeHist(gray,gray);
        MatOfRect faces=new MatOfRect();
        faceCascade.detectMultiScale(gray,faces,1.05,3,0,new Size(60,60),new Size());
        for(Rect r:faces.toArray()) Imgproc.rectangle(frame,r,new Scalar(0,220,120),3);
        gray.release();
        faces.release();
    }

    /**
     * Loads the actual MediaPipe palm detector exported to ONNX by OpenCV Zoo.
     * The model is downloaded once into the user's NexusID model cache so the
     * application ZIP does not need to carry a 3.7 MB binary model file.
     */
    public synchronized void preparePalmDetector() throws IOException {
        if(palmDetectorReady) return;
        lastPalmError=null;
        String configured=System.getenv("NEXUSID_PALM_MODEL");
        if(configured!=null && !configured.isBlank()){
            palmModelPath=Paths.get(configured);
            if(!Files.isRegularFile(palmModelPath)) throw new IOException("NEXUSID_PALM_MODEL does not point to a model file: "+palmModelPath);
        }else{
            Path dir=Paths.get(System.getProperty("user.home"),".nexusid","models");
            Files.createDirectories(dir);
            palmModelPath=dir.resolve("palm_detection_mediapipe_2023feb.onnx");
            if(!Files.isRegularFile(palmModelPath) || Files.size(palmModelPath)<500_000){
                downloadPalmModel(palmModelPath);
            }
        }
        palmNet=Dnn.readNet(palmModelPath.toString());
        if(palmNet.empty()) throw new IOException("Palm AI model could not be loaded.");
        palmNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        palmNet.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        palmAnchors=buildPalmAnchors();
        if(palmAnchors.size()!=2016) throw new IOException("Palm detector anchor setup is invalid: "+palmAnchors.size());
        palmDetectorReady=true;
        lastPalmError=null;
    }

    private void downloadPalmModel(Path target) throws IOException {
        Path partial=target.resolveSibling(target.getFileName()+".part");
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection) URI.create(PALM_MODEL_URL).toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent","NexusID-University/1.0");
            int code=connection.getResponseCode();
            if(code<200 || code>=300) throw new IOException("Palm model download failed (HTTP "+code+").");
            long expected=connection.getContentLengthLong();
            try(InputStream in=new BufferedInputStream(connection.getInputStream());
                OutputStream out=new BufferedOutputStream(Files.newOutputStream(partial,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING))){
                byte[] buffer=new byte[64*1024];
                long total=0;
                int n;
                while((n=in.read(buffer))!=-1){
                    out.write(buffer,0,n);
                    total+=n;
                }
                if(expected>0 && total!=expected) throw new IOException("Palm model download was incomplete.");
            }
            Files.move(partial,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException e){
            try{Files.deleteIfExists(partial);}catch(IOException ignored){}
            throw e;
        }finally{
            if(connection!=null) connection.disconnect();
        }
    }

    private List<Point> buildPalmAnchors(){
        List<Point> anchors=new ArrayList<>(2016);
        addAnchors(24,2,anchors);
        addAnchors(12,6,anchors);
        return anchors;
    }

    private void addAnchors(int grid,int anchorsPerCell,List<Point> out){
        for(int y=0;y<grid;y++){
            for(int x=0;x<grid;x++){
                float cx=(x+0.5f)/grid;
                float cy=(y+0.5f)/grid;
                for(int k=0;k<anchorsPerCell;k++) out.add(new Point(cx,cy));
            }
        }
    }

    public Mat detectPalm(Mat frame){
        if(frame==null||frame.empty()||!palmDetectorReady) return null;
        Mat blob=null;
        try{
            double scale=Math.min((double)PALM_INPUT/frame.cols(),(double)PALM_INPUT/frame.rows());
            int rw=Math.max(1,(int)Math.round(frame.cols()*scale));
            int rh=Math.max(1,(int)Math.round(frame.rows()*scale));
            Mat resized=new Mat();
            Imgproc.resize(frame,resized,new Size(rw,rh),0,0,Imgproc.INTER_LINEAR);
            int padW=PALM_INPUT-rw, padH=PALM_INPUT-rh;
            int left=padW/2, top=padH/2;
            Mat padded=Mat.zeros(PALM_INPUT,PALM_INPUT,frame.type());
            Mat roi=new Mat(padded,new Rect(left,top,rw,rh));
            resized.copyTo(roi);
            roi.release();
            resized.release();

            Mat rgb=new Mat();
            Imgproc.cvtColor(padded,rgb,Imgproc.COLOR_BGR2RGB);
            rgb.convertTo(rgb,CvType.CV_32FC3,1.0/255.0);
            float[] pixels=new float[PALM_INPUT*PALM_INPUT*3];
            rgb.get(0,0,pixels);
            int[] dims={1,PALM_INPUT,PALM_INPUT,3};
            blob=new Mat(dims,CvType.CV_32F);
            blob.put(new int[]{0,0,0,0},pixels);
            rgb.release();
            padded.release();

            palmNet.setInput(blob);
            List<Mat> outputs=new ArrayList<>();
            palmNet.forward(outputs,palmNet.getUnconnectedOutLayersNames());
            if(outputs.size()<2){ for(Mat m:outputs)m.release(); return null; }

            Mat boxes=outputs.get(0).reshape(1,(int)(outputs.get(0).total()/18));
            Mat scores=outputs.get(1).reshape(1,(int)outputs.get(1).total());
            List<Rect2d> candidates=new ArrayList<>();
            List<Float> candidateScores=new ArrayList<>();
            List<Point[]> candidateLandmarks=new ArrayList<>();
            double imageScale=Math.max(frame.cols(),frame.rows());
            for(int i=0;i<Math.min(boxes.rows(),palmAnchors.size());i++){
                double score=1.0/(1.0+Math.exp(-scores.get(i,0)[0]));
                if(score<PALM_SCORE_THRESHOLD) continue;
                double dx=boxes.get(i,0)[0]/PALM_INPUT;
                double dy=boxes.get(i,1)[0]/PALM_INPUT;
                double dw=boxes.get(i,2)[0]/PALM_INPUT;
                double dh=boxes.get(i,3)[0]/PALM_INPUT;
                Point a=palmAnchors.get(i);
                double cx=(dx+a.x)*imageScale;
                double cy=(dy+a.y)*imageScale;
                double ww=dw*imageScale;
                double hh=dh*imageScale;
                double x1=cx-ww/2.0-left/scale;
                double y1=cy-hh/2.0-top/scale;
                double x2=cx+ww/2.0-left/scale;
                double y2=cy+hh/2.0-top/scale;
                Rect2d r=new Rect2d(x1,y1,Math.max(1,x2-x1),Math.max(1,y2-y1));
                candidates.add(r);
                candidateScores.add((float)score);
                Point[] lm=new Point[7];
                for(int j=0;j<7;j++){
                    double lx=boxes.get(i,4+j*2)[0]/PALM_INPUT + a.x;
                    double ly=boxes.get(i,4+j*2+1)[0]/PALM_INPUT + a.y;
                    lm[j]=new Point(lx*imageScale-left/scale,ly*imageScale-top/scale);
                }
                candidateLandmarks.add(lm);
            }
            for(Mat m:outputs)m.release();
            if(candidates.isEmpty()){lastPalmRect=null;lastPalmConfidence=0;return null;}

            MatOfRect2d rects=new MatOfRect2d();
            rects.fromList(candidates);
            MatOfFloat confs=new MatOfFloat();
            float[] cs=new float[candidateScores.size()];
            for(int i=0;i<cs.length;i++)cs[i]=candidateScores.get(i);
            confs.fromArray(cs);
            MatOfInt indices=new MatOfInt();
            Dnn.NMSBoxes(rects,confs,(float)PALM_SCORE_THRESHOLD,(float)PALM_NMS_THRESHOLD,indices);
            int[] keep=indices.toArray();
            rects.release();confs.release();indices.release();
            if(keep.length==0){lastPalmRect=null;lastPalmConfidence=0;return null;}

            int bestIndex=keep[0];
            for(int idx:keep) if(candidateScores.get(idx)>candidateScores.get(bestIndex)) bestIndex=idx;
            Rect2d detected=candidates.get(bestIndex);
            Point[] lm=candidateLandmarks.get(bestIndex);

            double minX=frame.cols(),minY=frame.rows(),maxX=0,maxY=0;
            for(Point p:lm){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}
            double x1=Math.min(detected.x,minX),y1=Math.min(detected.y,minY);
            double x2=Math.max(detected.x+detected.width,maxX),y2=Math.max(detected.y+detected.height,maxY);
            double padX=Math.max(14,(x2-x1)*0.16),padY=Math.max(14,(y2-y1)*0.16);
            int ix=Math.max(0,(int)Math.floor(x1-padX));
            int iy=Math.max(0,(int)Math.floor(y1-padY));
            int ir=Math.min(frame.cols(),(int)Math.ceil(x2+padX));
            int ib=Math.min(frame.rows(),(int)Math.ceil(y2+padY));
            if(ir<=ix||ib<=iy)return null;
            Rect crop=new Rect(ix,iy,ir-ix,ib-iy);
            Mat hand=new Mat(frame,crop).clone();
            lastPalmRect=crop;
            lastPalmConfidence=candidateScores.get(bestIndex);
            return hand;
        }catch(Exception e){
            lastPalmRect=null;
            lastPalmConfidence=0;
            lastPalmError=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
            return null;
        }finally{
            if(blob!=null)blob.release();
        }
    }

    public String palmDetectorError(){ return lastPalmError; }

    public void drawPalmGuide(Mat frame, boolean detected){
        int w=frame.cols(),h=frame.rows();
        Rect guide=palmGuideRect(frame);
        Scalar c=detected?new Scalar(0,220,120):new Scalar(0,190,255);
        Imgproc.rectangle(frame,guide,c,3);
        if(detected && lastPalmRect!=null) Imgproc.rectangle(frame,lastPalmRect,new Scalar(0,255,0),3);
        String text=detected?String.format(Locale.US,"PALM DETECTED • %.0f%%",lastPalmConfidence*100):"PLACE OPEN PALM HERE";
        Imgproc.putText(frame,text,new Point(Math.max(10,guide.x+8),Math.max(25,guide.y-10)),Imgproc.FONT_HERSHEY_SIMPLEX,0.58,c,2);
    }

    private Rect palmGuideRect(Mat frame){
        int w=frame.cols(),h=frame.rows();
        int rw=Math.min(360,w-40),rh=Math.min(380,h-40);
        return new Rect(Math.max(0,(w-rw)/2),Math.max(0,(h-rh)/2),rw,rh);
    }

    public String signature(Mat image){
        Mat gray=new Mat();
        Mat small=new Mat();
        Mat f=new Mat();
        Mat dct=new Mat();
        try{
            Imgproc.cvtColor(image,gray,Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(gray,gray);
            Imgproc.resize(gray,small,new Size(32,32),0,0,Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(small,small,new Size(3,3),0);
            small.convertTo(f,CvType.CV_32F,1.0/255.0);
            Core.dct(f,dct);
            double norm=0;
            double[] values=new double[64];
            int k=0;
            for(int y=0;y<8;y++){
                for(int x=0;x<8;x++){
                    if(x==0&&y==0) continue;
                    if(k>=64) break;
                    double v=dct.get(y,x)[0];
                    values[k++]=v;
                    norm+=v*v;
                }
            }
            norm=Math.sqrt(Math.max(norm,1e-12));
            StringBuilder out=new StringBuilder("V2:");
            for(int i=0;i<64;i++){
                if(i>0) out.append(',');
                out.append(String.format(Locale.US,"%.6f",values[i]/norm));
            }
            return out.toString();
        }finally{
            gray.release(); small.release(); f.release(); dct.release();
        }
    }

    public double similarity(String a,String b){
        if(a==null||b==null) return 0;
        if(a.startsWith("V2:")&&b.startsWith("V2:")){
            String[] aa=a.substring(3).split(",");
            String[] bb=b.substring(3).split(",");
            if(aa.length!=bb.length||aa.length==0) return 0;
            double dot=0,na=0,nb=0;
            for(int i=0;i<aa.length;i++){
                double x=Double.parseDouble(aa[i]);
                double y=Double.parseDouble(bb[i]);
                dot+=x*y; na+=x*x; nb+=y*y;
            }
            if(na<=0||nb<=0) return 0;
            return Math.max(0,Math.min(1,dot/(Math.sqrt(na)*Math.sqrt(nb))));
        }
        if(a.length()!=b.length()||a.isEmpty()) return 0;
        int same=0;
        for(int i=0;i<a.length();i++) if(a.charAt(i)==b.charAt(i)) same++;
        return same/(double)a.length();
    }

    public double frameDifference(Mat a,Mat b){
        if(a==null||b==null||a.empty()||b.empty()) return 0;
        Mat g1=new Mat(),g2=new Mat(),d=new Mat();
        Imgproc.cvtColor(a,g1,Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(b,g2,Imgproc.COLOR_BGR2GRAY);
        Imgproc.resize(g1,g1,new Size(160,120));
        Imgproc.resize(g2,g2,new Size(160,120));
        Core.absdiff(g1,g2,d);
        double v=Core.mean(d).val[0];
        g1.release();g2.release();d.release();
        return v;
    }
}
