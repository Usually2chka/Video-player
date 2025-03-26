import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Point;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoPlayer {
    private FFmpegFrameGrabber grabber;
    private JFrame mainFrame;
    private VideoPanel videoPanel;
    private AtomicBoolean running = new AtomicBoolean(true);
    private AtomicBoolean paused = new AtomicBoolean(false);
    private AtomicBoolean userSeeking = new AtomicBoolean(false);
    private long startTime;
    private int frameCount = 0;
    private double fps = 0;
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
    private String currentFile;
    private float scaleFactor = 0.5f;
    private JSlider progressSlider;
    private long videoDuration;
    private long videoStartTimestamp;
    private Timer progressUpdateTimer;
    private AtomicInteger smoothSliderValue = new AtomicInteger(0);
    private static final int SLIDER_UPDATE_DELAY = 40;

    private static class VideoPanel extends JPanel {
        private volatile BufferedImage currentFrame;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentFrame != null) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(currentFrame, 0, 0, getWidth(), getHeight(), null);
            }
        }

        public void updateFrame(BufferedImage image) {
            this.currentFrame = image;
            repaint();
        }
    }

    public void play(String inputFile) {
        this.currentFile = inputFile;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);

        SwingUtilities.invokeLater(() -> {
            createMainWindow();

            try {
                initializeGrabber();
                resizeWindow();
                setupProgressTimer();
                new Thread(this::videoPlaybackLoop).start();
            } catch (Exception e) {
                showError("Ошибка инициализации: " + e.getMessage());
            }
        });
    }

    private void createMainWindow() {
        mainFrame = new JFrame("Видеоплеер");
        mainFrame.setLayout(new BorderLayout());
        mainFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        videoPanel = new VideoPanel();
        videoPanel.setBackground(Color.BLACK);
        mainFrame.add(videoPanel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new BorderLayout(5, 5));
        controlPanel.setBackground(new Color(240, 240, 240));

        progressSlider = new JSlider(0, 1000, 0);
        progressSlider.setUI(new SmoothSliderUI(progressSlider));
        progressSlider.setOpaque(false);
        progressSlider.setPreferredSize(new Dimension(300, 20));

        progressSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                userSeeking.set(true);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (userSeeking.get()) {
                    seekToSliderPosition();
                    userSeeking.set(false);
                }
            }
        });

        setupProgressTooltip();
        controlPanel.add(progressSlider, BorderLayout.NORTH);

        JPanel buttonPanel = createButtonPanel();
        controlPanel.add(buttonPanel, BorderLayout.CENTER);

        mainFrame.add(controlPanel, BorderLayout.SOUTH);
        mainFrame.pack();
        mainFrame.setLocationRelativeTo(null);
        mainFrame.setVisible(true);
    }

    private static class SmoothSliderUI extends BasicSliderUI {
        public SmoothSliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            super.paintThumb(g2d);
            g2d.dispose();
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            super.paintTrack(g2d);
            g2d.dispose();
        }
    }

    private void setupProgressTooltip() {
        progressSlider.addMouseMotionListener(new MouseAdapter() {
            private long lastUpdate = 0;

            @Override
            public void mouseMoved(MouseEvent e) {
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 100) {
                    updateTooltip(e.getX());
                    lastUpdate = now;
                }
            }

            private void updateTooltip(int x) {
                if (videoDuration > 0) {
                    float percent = x / (float)progressSlider.getWidth();
                    long time = (long)(percent * videoDuration / 1000);
                    progressSlider.setToolTipText(formatTime(time));
                }
            }
        });
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        panel.setBackground(new Color(240, 240, 240));

        JButton playPauseBtn = createControlButton("Пауза", new Color(76, 175, 80));
        JButton restartBtn = createControlButton("Заново", new Color(33, 150, 243));
        JButton zoomInBtn = createControlButton("+ Размер", new Color(158, 158, 158));
        JButton zoomOutBtn = createControlButton("- Размер", new Color(158, 158, 158));
        JButton exitBtn = createControlButton("Выход", new Color(244, 67, 54));

        playPauseBtn.addActionListener(e -> togglePause(playPauseBtn));
        restartBtn.addActionListener(e -> restartVideo());
        zoomInBtn.addActionListener(e -> adjustWindowSize(1.1f));
        zoomOutBtn.addActionListener(e -> adjustWindowSize(0.9f));
        exitBtn.addActionListener(e -> exitApplication());

        panel.add(playPauseBtn);
        panel.add(restartBtn);
        panel.add(zoomInBtn);
        panel.add(zoomOutBtn);
        panel.add(exitBtn);

        return panel;
    }

    private void setupProgressTimer() {
        progressUpdateTimer = new Timer(SLIDER_UPDATE_DELAY, e -> {
            if (grabber != null && !userSeeking.get()) {
                try {
                    long currentTime = grabber.getTimestamp() - videoStartTimestamp;
                    if (videoDuration > 0) {
                        int targetValue = (int)((currentTime * 1000) / videoDuration);
                        int currentValue = smoothSliderValue.get();
                        if (currentValue != targetValue) {
                            int newValue = currentValue + (int)((targetValue - currentValue) * 0.3);
                            smoothSliderValue.set(newValue);
                            SwingUtilities.invokeLater(() -> {
                                progressSlider.setValue(newValue);
                            });
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        progressUpdateTimer.start();
    }

    private void seekToSliderPosition() {
        if (grabber != null && videoDuration > 0) {
            try {
                long seekPos = (long)((progressSlider.getValue() * videoDuration) / 1000);
                grabber.setVideoTimestamp(videoStartTimestamp + seekPos);
                smoothSliderValue.set(progressSlider.getValue());
                frameCount = 0;
                startTime = System.currentTimeMillis() - (seekPos / 1000);
            } catch (Exception ex) {
                showError("Ошибка перемотки: " + ex.getMessage());
            }
        }
    }

    private JButton createControlButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setBackground(bgColor);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        button.setFont(new Font("Arial", Font.PLAIN, 12));
        return button;
    }

    private void updateFpsCounter() {
        frameCount++;
        if (frameCount % 10 == 0) {
            long currentTime = System.currentTimeMillis();
            double elapsedSeconds = (currentTime - startTime) / 1000.0;
            fps = frameCount / elapsedSeconds;

            if (elapsedSeconds > 2.0) {
                frameCount = 0;
                startTime = currentTime;
            }
        }
    }

    private void videoPlaybackLoop() {
        try (Java2DFrameConverter paintConverter = new Java2DFrameConverter()) {
            while (running.get()) {
                if (!paused.get() && !userSeeking.get()) {
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        restartVideo();
                        continue;
                    }

                    if (frame.image != null) {
                        frame = addDebugInfo(frame);
                        displayFrame(frame, paintConverter);
                        updateFpsCounter();
                    }

                    //Thread.sleep((long)(1000 / grabber.getFrameRate()));
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            showError("Ошибка воспроизведения: " + e.getMessage());
        } finally {
            closeResources();
        }
    }

    private void displayFrame(Frame frame, Java2DFrameConverter converter) {
        Image image = converter.convert(frame);
        BufferedImage bufferedImage = new BufferedImage(
                Math.max(videoPanel.getWidth(), 1),
                Math.max(videoPanel.getHeight(), 1),
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(image, 0, 0, bufferedImage.getWidth(), bufferedImage.getHeight(), null);
        g2d.dispose();

        SwingUtilities.invokeLater(() -> videoPanel.updateFrame(bufferedImage));
    }

    private void adjustWindowSize(float factor) {
        scaleFactor = Math.max(0.2f, Math.min(1.5f, scaleFactor * factor));
        resizeWindow();
    }

    private void resizeWindow() {
        try {
            int width = (int)(grabber.getImageWidth() * scaleFactor);
            int height = (int)(grabber.getImageHeight() * scaleFactor) + 100;

            mainFrame.setPreferredSize(new Dimension(width, height));
            mainFrame.pack();
        } catch (Exception e) {
            showError("Ошибка изменения размера: " + e.getMessage());
        }
    }

    private void initializeGrabber() throws Exception {
        grabber = new FFmpegFrameGrabber(currentFile);
        grabber.setOption("hwaccel", "auto");
        grabber.start();

        videoDuration = grabber.getLengthInTime();
        videoStartTimestamp = grabber.getTimestamp();
        startTime = System.currentTimeMillis();
    }

    private void restartVideo() {
        try {
            grabber.restart();
            smoothSliderValue.set(0);
            frameCount = 0;
            startTime = System.currentTimeMillis();
            SwingUtilities.invokeLater(() -> progressSlider.setValue(0));
        } catch (Exception ex) {
            showError("Ошибка перезапуска: " + ex.getMessage());
        }
    }

    private Frame addDebugInfo(Frame frame) {
        Mat mat = converter.convert(frame);

        if (mat.channels() == 3) {
            opencv_imgproc.cvtColor(mat, mat, opencv_imgproc.COLOR_RGB2BGR);
        }

        long currentTime = System.currentTimeMillis() - startTime;
        String timeStr = String.format("Time: %.1fs", currentTime / 1000.0);
        String frameStr = String.format("Frame: %d", frameCount);
        String fpsStr = String.format("FPS: %.1f", fps);

        Scalar color = new Scalar(0.0, 0.0, 0.0, 0.0);
        int fontFace = opencv_imgproc.FONT_HERSHEY_PLAIN;
        double fontScale = 1.0;

        opencv_imgproc.putText(mat, timeStr, new Point(10, 20),
                fontFace, fontScale, color);
        opencv_imgproc.putText(mat, frameStr, new Point(10, 40),
                fontFace, fontScale, color);
        opencv_imgproc.putText(mat, fpsStr, new Point(10, 60),
                fontFace, fontScale, color);

        opencv_imgproc.cvtColor(mat, mat, opencv_imgproc.COLOR_BGR2RGB);

        return converter.convert(mat);
    }

    private void togglePause(JButton button) {
        paused.set(!paused.get());
        button.setText(paused.get() ? "Продолжить" : "Пауза");
    }

    private void exitApplication() {
        running.set(false);
        if (progressUpdateTimer != null) {
            progressUpdateTimer.stop();
        }
        SwingUtilities.invokeLater(() -> mainFrame.dispose());
    }

    private void closeResources() {
        try {
            if (grabber != null) grabber.stop();
            if (progressUpdateTimer != null) progressUpdateTimer.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(mainFrame, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        });
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Использование: VideoPlayer <видеофайл>");
            return;
        }

        new VideoPlayer().play(args[0]);
    }
}