package com.nexusid.ui;

import com.nexusid.service.BiometricService;
import com.nexusid.dao.UniversityDAO;
import com.nexusid.service.CameraBiometricService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BiometricCaptureView {
    public enum Mode { FACE_ENROLL, FACE_VERIFY, PALM_ENROLL, PALM_VERIFY, LIVENESS }
    private final long userId;
    private final BiometricService biometric;
    private final boolean securityAccessMode;
    private final UniversityDAO university = new UniversityDAO();
    private Stage stage;
    private ImageView preview;
    private Label status;
    private Label cameraStatus;
    private Button actionButton;
    private CameraBiometricService cameraService;
    private VideoCapture camera;
    private volatile boolean running;
    private volatile boolean captureRequested;
    private volatile boolean livenessRequested;
    private volatile boolean holdResult;
    private Thread cameraThread;
    private boolean cameraLeaseHeld;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean stageClosed = new AtomicBoolean(false);
    private Mode currentMode;
    private Mat latestFrame;

    public BiometricCaptureView(long userId, BiometricService biometric) { this(userId, biometric, false); }
    public BiometricCaptureView(long userId, BiometricService biometric, boolean securityAccessMode) { this.userId=userId; this.biometric=biometric; this.securityAccessMode=securityAccessMode; }

    private void recordAccess(String method, boolean granted, long matchedUserId) {
        if (!securityAccessMode) return;
        Long targetUserId = granted ? matchedUserId : null;
        try { university.logAccess(userId, targetUserId, method, "ENTRY", "Main Gate", granted ? "GRANTED" : "DENIED"); }
        catch (Exception ex) { Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Verification finished, but access logging failed: " + ex.getMessage()).showAndWait()); }
    }

    private record BiometricMatch(long userId, double score) {}

    private BiometricMatch findBestMatch(String kind, String current) throws Exception {
        long bestId = userId;
        double bestScore = 0.0;
        for (var entry : biometric.templates(userId, kind).entrySet()) {
            double score = cameraService.similarity(entry.getValue(), current);
            if (score > bestScore) { bestScore = score; bestId = entry.getKey(); }
        }
        return new BiometricMatch(bestId, bestScore);
    }

    public void show(Stage owner, Mode mode) {
        currentMode=mode;
        stopRequested.set(false);
        stageClosed.set(false);
        stage=new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("NexusID Biometric Verification");

        VBox root=new VBox(14);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("aurora-biometric-root");
        Label title=new Label(label(mode));
        title.getStyleClass().add("aurora-page-title");

        preview=new ImageView();
        preview.setFitWidth(640);
        preview.setFitHeight(430);
        preview.setPreserveRatio(true);
        StackPane frame=new StackPane(preview);
        frame.setPrefSize(640,430);
        frame.getStyleClass().add("aurora-camera-frame");

        cameraStatus=new Label("Camera: starting…");
        cameraStatus.getStyleClass().add("aurora-camera-status");
        status=new Label(instruction(mode));
        status.setWrapText(true);

        actionButton=new Button(actionText(mode));
        actionButton.setPrefHeight(40);
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.setDisable(true);
        actionButton.setOnAction(e->{
            holdResult=false;
            if(currentMode==Mode.LIVENESS){
                livenessRequested=true;
            }else{
                captureRequested=true;
            }
        });

        Button close=new Button("CLOSE");
        close.setPrefHeight(38);
        close.setMaxWidth(Double.MAX_VALUE);
        close.setOnAction(e->stop());

        root.getChildren().addAll(title,frame,cameraStatus,status,actionButton,close);
        stage.setScene(AuroraTheme.attach(new Scene(root,760,780)));
        stage.setOnCloseRequest(e->{ e.consume(); stop(); });
        stage.setOnHidden(e->stopRequested.set(true));
        stage.show();
        // Check enrollment before opening the camera so a missing template is not
        // incorrectly reported as a camera failure. Do not alter profile state.
        if (!securityAccessMode && (mode==Mode.FACE_VERIFY || mode==Mode.PALM_VERIFY)) {
            String kind=mode==Mode.FACE_VERIFY?"FACE":"PALM";
            try {
                if (biometric.template(userId,kind)==null) {
                    cameraStatus.setText("Verification unavailable");
                    cameraStatus.setStyle("-fx-text-fill:#92400e;-fx-font-weight:bold;");
                    status.setText((kind.equals("FACE")?"Face":"Palm")+
                            " not enrolled or disabled. Please enroll and enable this modality before verification.");
                    actionButton.setDisable(true);
                    return;
                }
            } catch (Exception ex) {
                cameraStatus.setText("Verification unavailable");
                cameraStatus.setStyle("-fx-text-fill:#b91c1c;-fx-font-weight:bold;");
                status.setText("Unable to check biometric enrollment: "+ex.getMessage());
                actionButton.setDisable(true);
                return;
            }
        }
        startCamera();
    }

    public void showSimulation(Stage owner, Mode mode) {
        Alert a=new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("NexusID Software Test Mode");
        a.setHeaderText(label(mode));
        a.setContentText("No biometric hardware is required. This software test will exercise the database workflow without camera input. Continue?");
        if(a.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        try {
            if(mode==Mode.FACE_ENROLL) biometric.saveTemplate(userId,"FACE",simulated("FACE"));
            else if(mode==Mode.PALM_ENROLL) biometric.saveTemplate(userId,"PALM",simulated("PALM"));
            else if(mode==Mode.LIVENESS) biometric.enableLiveness(userId);
            else verifySimulation(mode==Mode.FACE_VERIFY?"FACE":"PALM");
            new Alert(Alert.AlertType.INFORMATION,"Software biometric test completed successfully.").showAndWait();
        } catch(Exception e){new Alert(Alert.AlertType.ERROR,"Biometric test failed: "+e.getMessage()).showAndWait();}
    }

    private void startCamera(){
        if(running)return;
        running=true;
        stopRequested.set(false);
        cameraThread=new Thread(()->{
            try{
                cameraService=new CameraBiometricService();
                VideoCapture opened=cameraService.openCamera();
                synchronized(this){ camera=opened; cameraLeaseHeld=opened.isOpened(); }
                if(!opened.isOpened()) throw new IllegalStateException("Camera could not be opened. Check camera permission and that no other app is using it.");
                Platform.runLater(()->{
                    if(stopRequested.get())return;
                    cameraStatus.setText("Camera: Connected");
                    cameraStatus.setStyle("-fx-text-fill:#15803d;-fx-font-weight:bold;");
                    status.setText(instruction(currentMode));
                });
                // Do not perform a blocking warm-up read here. On macOS/AVFoundation a
                // camera can report opened while the first native read temporarily waits.
                // The preview loop below owns all reads and can recover from empty frames.
                if(currentMode==Mode.PALM_ENROLL || currentMode==Mode.PALM_VERIFY){
                    Platform.runLater(()->{
                        actionButton.setDisable(true);
                        status.setText("Loading AI palm detector… first run may download the palm model once.");
                    });
                    cameraService.preparePalmDetector();
                    Platform.runLater(()->{
                        if(stopRequested.get())return;
                        actionButton.setDisable(false);
                        status.setText("AI palm detector ready • place your open palm inside the guide.");
                    });
                }else{
                    Platform.runLater(()->actionButton.setDisable(false));
                }
                previewLoop();
            }catch(Exception e){
                Platform.runLater(()->{
                    if(stopRequested.get())return;
                    cameraStatus.setText("Camera: Error");
                    cameraStatus.setStyle("-fx-text-fill:#b91c1c;-fx-font-weight:bold;");
                    status.setText("Camera error: "+e.getMessage());
                    actionButton.setDisable(true);
                });
            }finally{
                releaseCamera();
                running=false;
                Platform.runLater(()->{
                    if(stopRequested.get() && stage!=null && stage.isShowing() && stageClosed.compareAndSet(false,true)) stage.close();
                });
            }
        },"nexusid-camera");
        cameraThread.setDaemon(true);
        cameraThread.start();
    }

    private synchronized void releaseCamera(){
        camera=null;
        if(cameraLeaseHeld){
            cameraLeaseHeld=false;
            if(cameraService!=null) cameraService.releaseCameraLease();
        }
        if(latestFrame!=null){
            try{ latestFrame.release(); }catch(Exception ignored){}
            latestFrame=null;
        }
    }

    private void previewLoop() throws Exception{
        int emptyReads=0;
        boolean firstFrame=false;
        long lastFrameAt=System.currentTimeMillis();
        while(running && !stopRequested.get() && stage.isShowing()){
            Mat frame=new Mat();
            VideoCapture c=camera;
            if(c==null || !c.isOpened()){
                frame.release();
                Thread.sleep(120);
                continue;
            }
            boolean readOk=false;
            try{ readOk=c.read(frame); }catch(Exception ignored){}
            if(!readOk || frame.empty()){
                frame.release();
                emptyReads++;
                if(emptyReads==1){
                    Platform.runLater(()->status.setText("Camera connected • waiting for live frames…"));
                }
                // Recover automatically instead of forcing the user to restart the whole app.
                if(emptyReads>=20 || System.currentTimeMillis()-lastFrameAt>3000){
                    Platform.runLater(()->cameraStatus.setText("Camera: Reconnecting…"));
                    synchronized(this){
                        camera=null;
                        if(cameraLeaseHeld){
                            cameraLeaseHeld=false;
                            cameraService.releaseCameraLease();
                        }
                    }
                    Thread.sleep(250);
                    if(stopRequested.get() || !stage.isShowing()) break;
                    VideoCapture reopened=cameraService.openCamera();
                    synchronized(this){camera=reopened; cameraLeaseHeld=reopened.isOpened();}
                    emptyReads=0;
                    if(reopened.isOpened()) Platform.runLater(()->cameraStatus.setText("Camera: Connected"));
                    lastFrameAt=System.currentTimeMillis();
                }else Thread.sleep(80);
                continue;
            }
            emptyReads=0;
            lastFrameAt=System.currentTimeMillis();
            if(!firstFrame){
                firstFrame=true;
                Platform.runLater(()->{
                    cameraStatus.setText("Camera: Connected");
                    cameraStatus.setStyle("-fx-text-fill:#15803d;-fx-font-weight:bold;");
                    actionButton.setDisable(false);
                    status.setText(instruction(currentMode));
                });
            }
            boolean faceMode=currentMode==Mode.FACE_ENROLL||currentMode==Mode.FACE_VERIFY;
            if(currentMode==Mode.LIVENESS && livenessRequested){
                livenessRequested=false;
                holdResult=true;
                runLiveness();
                holdResult=false;
                frame.release();
                continue;
            }
            Mat region=faceMode?cameraService.findFace(frame):cameraService.detectPalm(frame);
            boolean detected=region!=null;
            if(faceMode) cameraService.drawFaceBox(frame); else cameraService.drawPalmGuide(frame, detected);
            updatePreview(frame);
            if(region!=null)region.release();
            if(captureRequested){
                captureRequested=false;
                holdResult=true;
                if(currentMode==Mode.FACE_VERIFY) performIntegratedFaceVerification();
                else captureCurrent(currentMode);
            }else if(!holdResult){
                String type=currentMode==Mode.FACE_ENROLL||currentMode==Mode.FACE_VERIFY?"Face":"Palm";
                String detectorError=cameraService.palmDetectorError();
                Platform.runLater(()->status.setText(detected?type+" detected • ready for capture.":(detectorError!=null?"Palm detector error: "+detectorError:type+" not detected • adjust your position and lighting.")));
            }
            frame.release();
            Thread.sleep(90);
        }
    }

    private void performIntegratedFaceVerification() throws Exception{
        Platform.runLater(()->{
            actionButton.setDisable(true);
            actionButton.setText("LIVENESS CHECK RUNNING…");
            status.setText("Face detected • liveness check starting…");
        });
        boolean live=runInteractiveLiveness();
        if(!live){
            Platform.runLater(()->{
                holdResult=false;
                actionButton.setDisable(false);
                actionButton.setText("VERIFY FACE");
                status.setText("LIVENESS NOT CONFIRMED • follow the on-screen head-movement instruction and try again.");
            });
            return;
        }
        captureFaceVerification();
    }

    private boolean runInteractiveLiveness() throws Exception{
        Mat previousFace=null;
        double movement=0;
        int valid=0;
        long end=System.currentTimeMillis()+6500;
        while(System.currentTimeMillis()<end && running && !stopRequested.get()){
            Mat frame=new Mat();
            VideoCapture c=camera;
            if(c==null || !c.isOpened()){ frame.release(); Thread.sleep(60); continue; }
            if(!c.read(frame)||frame.empty()){ frame.release(); Thread.sleep(60); continue; }
            Mat face=cameraService.findFace(frame);
            if(face!=null){
                valid++;
                if(previousFace!=null) movement+=cameraService.frameDifference(previousFace,face);
                if(previousFace!=null) previousFace.release();
                previousFace=face.clone();
                face.release();
                Platform.runLater(()->status.setText("Face detected • make a small natural head movement…"));
            }else{
                Platform.runLater(()->status.setText("Face not detected • keep your face inside the preview."));
            }
            cameraService.drawFaceBox(frame);
            updatePreview(frame);
            frame.release();
            Thread.sleep(100);
        }
        if(previousFace!=null) previousFace.release();
        double averageMovement=movement/Math.max(1,valid-1);
        boolean passed=valid>=8 && averageMovement>2.0;
        if(passed) biometric.enableLiveness(userId);
        return passed;
    }

    private void captureFaceVerification() throws Exception{
        List<String> samples=new ArrayList<>();
        long end=System.currentTimeMillis()+4500;
        while(System.currentTimeMillis()<end && samples.size()<3 && running && !stopRequested.get()){
            Mat frame=new Mat();
            VideoCapture c=camera;
            if(c==null || !c.isOpened()){frame.release();continue;}
            if(!c.read(frame)||frame.empty()){frame.release();continue;}
            Mat region=cameraService.findFace(frame);
            if(region!=null){
                samples.add(cameraService.signature(region));
                region.release();
                int n=samples.size();
                Platform.runLater(()->status.setText("Liveness passed • face sample "+n+" captured…"));
            }
            cameraService.drawFaceBox(frame);
            updatePreview(frame);
            frame.release();
            Thread.sleep(120);
        }
        if(samples.isEmpty()){
            Platform.runLater(()->{
                holdResult=false;
                actionButton.setDisable(false);
                actionButton.setText("VERIFY FACE");
                status.setText("Face not detected after liveness. Please center your face and try again.");
            });
            return;
        }
        String current=combine(samples);
        BiometricMatch match;
        if (securityAccessMode) match = findBestMatch("FACE", current);
        else {
            String stored=biometric.template(userId,"FACE");
            if(stored==null) throw new IllegalStateException("Enroll face first.");
            match = new BiometricMatch(userId, cameraService.similarity(stored,current));
        }
        double score=match.score();
        boolean ok=score>=0.72;
        recordAccess("FACE", ok, match.userId());
        Platform.runLater(()->{
            actionButton.setDisable(false);
            actionButton.setText("VERIFY FACE AGAIN");
            status.setText((ok?"VERIFIED":"NOT VERIFIED")+" • similarity "+String.format("%.1f",score*100)+"%\nLiveness passed • result is held on screen.");
        });
    }

    private void captureCurrent(Mode mode) throws Exception{
        boolean face=mode==Mode.FACE_ENROLL||mode==Mode.FACE_VERIFY;
        List<String> samples=new ArrayList<>();
        long end=System.currentTimeMillis()+4500;
        while(System.currentTimeMillis()<end && samples.size()<(mode==Mode.FACE_ENROLL||mode==Mode.PALM_ENROLL?6:3)){
            Mat frame=new Mat();
            VideoCapture c=camera;
            if(c==null || !c.isOpened()){frame.release();continue;}
            if(!c.read(frame)||frame.empty()){frame.release();continue;}
            Mat region=face?cameraService.findFace(frame):cameraService.detectPalm(frame);
            if(region!=null){
                samples.add(cameraService.signature(region));
                region.release();
                Platform.runLater(()->status.setText((face?"Face":"Palm")+" sample "+samples.size()+" captured…"));
            }
            if(face)cameraService.drawFaceBox(frame); else cameraService.drawPalmGuide(frame, region!=null);
            updatePreview(frame);
            frame.release();
            Thread.sleep(120);
        }
        if(samples.isEmpty()){
            Platform.runLater(()->status.setText(face?"Face not detected. Please center your face and try again.":"Palm not detected. Place your palm in the center and try again."));
            return;
        }
        String current=combine(samples);
        if(mode==Mode.FACE_ENROLL||mode==Mode.PALM_ENROLL){
            biometric.saveTemplate(userId,face?"FACE":"PALM",current);
            Platform.runLater(()->{
                status.setText((face?"Face":"Palm")+" enrollment completed and the template was saved to the university identity.");
                actionButton.setText("CAPTURE / RE-ENROLL");
            });
        }else{
            String kind=face?"FACE":"PALM";
            BiometricMatch match;
            if (securityAccessMode) match = findBestMatch(kind, current);
            else {
                String stored=biometric.template(userId,kind);
                if(stored==null) throw new IllegalStateException("Enroll "+(face?"face":"palm")+" first.");
                match = new BiometricMatch(userId, cameraService.similarity(stored,current));
            }
            double score=match.score();
            boolean ok=score>=0.72;
            recordAccess(kind, ok, match.userId());
            Platform.runLater(()->{
                status.setText((ok?"VERIFIED":"NOT VERIFIED")+" • similarity "+String.format("%.1f",score*100)+"%\nResult is held on screen. Press VERIFY AGAIN to run another check.");
                actionButton.setText("VERIFY AGAIN");
            });
        }
    }

    private void runLiveness() throws Exception{
        Mat previous=null;
        double movement=0;
        int valid=0;
        long end=System.currentTimeMillis()+9000;
        Platform.runLater(()->{
            actionButton.setText("LIVENESS CHECK RUNNING…");
            actionButton.setDisable(true);
            status.setText("Liveness check running • make a small natural head movement…");
        });
        try{
            while(System.currentTimeMillis()<end && running && !stopRequested.get() && stage.isShowing()){
                Mat frame=new Mat();
                VideoCapture c=camera;
                if(c==null || !c.isOpened()){
                    frame.release();
                    Thread.sleep(80);
                    continue;
                }
                boolean ok=false;
                try{ ok=c.read(frame); }catch(Exception ignored){}
                if(!ok || frame.empty()){
                    frame.release();
                    Thread.sleep(80);
                    continue;
                }
                Mat face=cameraService.findFace(frame);
                if(face!=null){
                    valid++;
                    if(previous!=null) movement+=cameraService.frameDifference(previous,face);
                    if(previous!=null) previous.release();
                    previous=face.clone();
                    face.release();
                    Platform.runLater(()->status.setText("Face detected • make a small natural head movement…"));
                }else{
                    Platform.runLater(()->status.setText("Face not detected • position your face inside the preview."));
                }
                cameraService.drawFaceBox(frame);
                updatePreview(frame);
                frame.release();
                Thread.sleep(100);
            }
        }finally{
            if(previous!=null) previous.release();
        }
        double averageMovement=movement/Math.max(1,valid-1);
        boolean passed=valid>=5 && averageMovement>2.0;
        if(passed) biometric.enableLiveness(userId);
        Platform.runLater(()->{
            actionButton.setDisable(false);
            actionButton.setText("RUN LIVENESS CHECK AGAIN");
            status.setText(passed
                    ? "LIVENESS PASSED • natural face movement detected and status saved."
                    : "LIVENESS NOT CONFIRMED • move your head naturally and try again.");
        });
    }

    private void updatePreview(Mat frame){
        Image image=toImage(frame);
        if(image!=null)Platform.runLater(()->preview.setImage(image));
    }

    private void verifySimulation(String kind)throws Exception{
        String stored=biometric.template(userId,kind);
        if(stored==null)throw new IllegalStateException("Enroll "+kind.toLowerCase()+" first.");
        double score=biometricSimilarity(stored,simulated(kind));
        new Alert(Alert.AlertType.INFORMATION,(kind+" verification: "+(score>=0.65?"VERIFIED":"NOT VERIFIED")+"\nSimilarity: "+String.format("%.1f",score*100)+"%")).showAndWait();
    }

    private double biometricSimilarity(String a,String b){
        if(a==null||b==null||a.length()!=b.length())return 0;
        int same=0;for(int i=0;i<a.length();i++)if(a.charAt(i)==b.charAt(i))same++;
        return same/(double)a.length();
    }

    private String simulated(String kind){return "NEXUSID-DEMO-"+kind+"-"+userId+"-SOFTWARE-TEMPLATE";}

    private String combine(List<String> xs){
        if(xs.size()==1) return xs.get(0);
        if(xs.get(0).startsWith("V2:")){
            String[] first=xs.get(0).substring(3).split(",");
            double[] sum=new double[first.length];
            int valid=0;
            for(String s:xs){
                if(!s.startsWith("V2:")) continue;
                String[] parts=s.substring(3).split(",");
                if(parts.length!=first.length) continue;
                for(int i=0;i<parts.length;i++) sum[i]+=Double.parseDouble(parts[i]);
                valid++;
            }
            if(valid>0){
                double norm=0;
                for(double v:sum) norm+=(v/valid)*(v/valid);
                norm=Math.sqrt(Math.max(norm,1e-12));
                StringBuilder out=new StringBuilder("V2:");
                for(int i=0;i<sum.length;i++){
                    if(i>0) out.append(',');
                    out.append(String.format(java.util.Locale.US,"%.6f",(sum[i]/valid)/norm));
                }
                return out.toString();
            }
        }
        StringBuilder out=new StringBuilder(xs.get(0).length());
        int n=xs.get(0).length();
        for(int i=0;i<n;i++){
            int ones=0;
            for(String s:xs) if(s.charAt(i)=='1') ones++;
            out.append(ones*2>=xs.size()?'1':'0');
        }
        return out.toString();
    }

    private String label(Mode m){return switch(m){case FACE_ENROLL->"Face Enrollment";case FACE_VERIFY->"Face Verification";case PALM_ENROLL->"Palm Enrollment";case PALM_VERIFY->"Palm Verification";case LIVENESS->"Liveness Check";};}
    private String actionText(Mode m){return switch(m){case FACE_ENROLL->"CAPTURE / ENROLL FACE";case FACE_VERIFY->"VERIFY FACE";case PALM_ENROLL->"CAPTURE / ENROLL PALM";case PALM_VERIFY->"VERIFY PALM";case LIVENESS->"RUN LIVENESS CHECK";};}
    private String instruction(Mode m){return switch(m){case FACE_ENROLL->"Live preview is active. Center your face, wait for the green box, then capture several samples.";case FACE_VERIFY->"Live preview is active. Center your face, wait for the green box, then press VERIFY FACE.";case PALM_ENROLL->"Live preview is active. The AI palm detector will locate and crop your palm automatically.";case PALM_VERIFY->"Live preview is active. The AI palm detector will locate and crop your palm automatically.";case LIVENESS->"Live preview is active. The system will check natural face movement.";};}

    private Image toImage(Mat mat){
        try{
            Mat rgb=new Mat();
            org.opencv.imgproc.Imgproc.cvtColor(mat,rgb,org.opencv.imgproc.Imgproc.COLOR_BGR2RGB);
            int w=rgb.cols(),h=rgb.rows();
            byte[] data=new byte[(int)(rgb.total()*rgb.channels())];
            rgb.get(0,0,data);
            BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_3BYTE_BGR);
            byte[] target=((java.awt.image.DataBufferByte)b.getRaster().getDataBuffer()).getData();
            System.arraycopy(data,0,target,0,data.length);
            rgb.release();
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            ImageIO.write(b,"jpg",out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        }catch(Exception e){return null;}
    }

    private void stop(){
        stopRequested.set(true);
        running=false;
        captureRequested=false;
        livenessRequested=false;
        holdResult=false;
        // The camera thread owns the VideoCapture object. Do not release it from the JavaFX thread.
        // This prevents the native OpenCV camera object from being released concurrently while read() is running.
        Thread t=cameraThread;
        if(t!=null && t!=Thread.currentThread()) t.interrupt();
        if(stage!=null && stage.isShowing() && stageClosed.compareAndSet(false,true)) stage.close();
    }

    public static Mode faceEnroll(){return Mode.FACE_ENROLL;}
    public static Mode faceVerify(){return Mode.FACE_VERIFY;}
    public static Mode palmEnroll(){return Mode.PALM_ENROLL;}
    public static Mode palmVerify(){return Mode.PALM_VERIFY;}
    public static Mode liveness(){return Mode.LIVENESS;}
}
