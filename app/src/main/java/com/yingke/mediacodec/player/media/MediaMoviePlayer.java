package com.yingke.mediacodec.player.media;

import static android.media.projection.MediaProjection.*;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.yingke.mediacodec.player.PlayerLog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;


public class MediaMoviePlayer {

    private static final int TIMEOUT_USEC = 10000;	// 10msec

    private static final int STATE_STOP = 0;
    private static final int STATE_PREPARED = 1;
    private static final int STATE_PLAYING = 2;
    private static final int STATE_PAUSED = 3;

    // request code
    private static final int REQUEST_NON = 0;
    private static final int REQUEST_PREPARE = 1;
    private static final int REQUEST_START = 2;
    private static final int REQUEST_SEEK = 3;
    private static final int REQUEST_STOP = 4;
    private static final int REQUEST_PAUSE = 5;
    private static final int REQUEST_RESUME = 6;
    private static final int REQUEST_QUIT = 9;

    public static final boolean DEBUG = true;
    public static final String TAG_STATIC = "MediaMoviePlayer";
    public static final String TAG = TAG_STATIC;

    private Context mContext;

    private final IPlayerListener mCallback;
    private final boolean mAudioEnabled;


//    private final Object mSync = new Object();
    private volatile boolean mIsRunning;

    // 播放状态
    private int mPlayerState;
    // 播放请求
    private int mPlayerRequest;
    // 请求时间
    private long mRequestTime;
    // 视频地址
    private String mSourcePath;
    // 时长
    private long mDuration;
    // 媒体信息
    private MediaMetadataRetriever mMediaMetadata;

    private final Object mPauseResumeSync = new Object();

    private volatile boolean mIsPaused = false;

    // 视频
    private final Object mVideoSync = new Object();
    // 输出Surface
    private Surface mOutputSurface;
    private static ImageWriter mOutputImageWriter;

    private Surface mImageReaderSurface;

    // 视频解码器
    private MediaCodec mVideoMediaCodec;
    protected MediaExtractor mVideoMediaExtractor;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private ByteBuffer[] mVideoInputBuffers;
    private ByteBuffer[] mVideoOutputBuffers;
    // 视频轨道索引
    private volatile int mVideoTrackIndex;
    private volatile boolean mVideoInputDone;
    private volatile boolean mVideoOutputDone;
    private long previousVideoPresentationTimeUs = -1;

    // 视频信息
    private long mVideoStartTime;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mBitrate;
    private float mFrameRate;
    private int mRotation;

    public int getWidth() { return mVideoWidth; }

    public int getHeight() { return mVideoHeight; }


    private ImageReader mImageReader;
    private Handler mImageReaderCallbackHandler;

    public MediaMoviePlayer(Context context, final Surface outputSurface, final IPlayerListener callback, final boolean audioEnable) {
        PlayerLog.w("Constructor:");

        mContext = context;
        mOutputSurface = outputSurface;

        mCallback = callback;
        mAudioEnabled = audioEnable;

        mIsRunning = false;

        mPlayerState = STATE_STOP;
        mPlayerRequest = REQUEST_NON;
        mRequestTime = -1;
    }

    public final void prepare() throws Exception {
        PlayerLog.w("prepare:");

        mOutputImageWriter = ImageWriter.newInstance(mOutputSurface, 1);

        // 初始化所有需要的数据结构，准备解码
        mMediaMetadata = new MediaMetadataRetriever();
        mMediaMetadata.setDataSource(mSourcePath);

        mVideoMediaExtractor = new MediaExtractor();
        mVideoMediaExtractor.setDataSource(mSourcePath);
        mVideoTrackIndex = selectTrack(mVideoMediaExtractor, "video/");
        mVideoMediaExtractor.selectTrack(mVideoTrackIndex);
        final MediaFormat mediaFormat = mVideoMediaExtractor.getTrackFormat(mVideoTrackIndex);
        mVideoWidth = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        mVideoHeight = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        mDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
        mFrameRate = mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
        final String mediaMime = mediaFormat.getString(MediaFormat.KEY_MIME);

        mImageReader = ImageReader.newInstance(mVideoWidth, mVideoHeight, ImageFormat.YUV_420_888, 3);
        mImageReader.setOnImageAvailableListener(new OnImageListenser(), mImageReaderCallbackHandler);
        mImageReaderSurface = mImageReader.getSurface();

        mVideoMediaCodec = MediaCodec.createDecoderByType(mediaMime);
        mVideoMediaCodec.configure(mediaFormat, mImageReaderSurface, null, 0);
        mVideoMediaCodec.start();

        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mVideoInputBuffers = mVideoMediaCodec.getInputBuffers();
        mVideoOutputBuffers = mVideoMediaCodec.getOutputBuffers();

        mVideoInputDone = false;
        mVideoOutputDone = false;

        if (mCallback != null) {
            mCallback.onPrepared();
        }
    }

    public final void release() throws IOException {
        mVideoMediaCodec.stop();
        mVideoMediaCodec.release();
        mVideoMediaExtractor.release();
        mMediaMetadata.release();
    }

    public final void play() {
        mIsRunning = true;
        new Thread(mVideoTask, "VideoTask").start();
    }

    public final void pause() {

    }

    public final void resume() {

    }

    private final Runnable mVideoTask = new Runnable() {

        @Override
        public void run() {
            for (; mIsRunning && !mVideoInputDone && !mVideoOutputDone;) {
                // 循环
                try {

                    if (mIsPaused) {
                        synchronized (mPauseResumeSync) {
                            mPauseResumeSync.wait();
                        }
                    }

                    // 读一帧数据给 解码器
                    if (!mVideoInputDone) {
                        handleInputVideo();
                    }
                    // 输出一帧数据到 surface
                    if (!mVideoOutputDone) {
                        handleOutputVideo(mCallback);
                    }

                } catch (final Exception e) {
                    Log.e(TAG, "VideoTask:", e);
                    break;
                }
            }

            if (DEBUG) Log.v(TAG, "VideoTask:finished");
            synchronized (mVideoTask) {
                mVideoInputDone = mVideoOutputDone = true;
                mVideoTask.notifyAll();
            }

            if (mCallback != null) {
                mCallback.onFinished();
            }
        }
    };

    /**
     * 处理输入视频：读一帧视频数据给解码器
     */
    private final void handleInputVideo() {
        final long presentationTimeUs = mVideoMediaExtractor.getSampleTime();

        // 读一帧视频数据给解码器
        final boolean b = internalProcessInput(mVideoMediaCodec, mVideoMediaExtractor, mVideoInputBuffers, presentationTimeUs, false);
        if (!b) {
            // 读结束
            if (DEBUG) {
                Log.i(TAG, "video track input reached EOS");
            }
            while (mIsRunning) {
                // 向解码器 写入 flag = END_OF_STREAM的Buffer
                final int inputBufIndex = mVideoMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);

                if (inputBufIndex >= 0) {
                    mVideoMediaCodec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    if (DEBUG) {
                        Log.v(TAG, "sent input EOS:" + mVideoMediaCodec);
                    }
                    break;
                }
            }
            synchronized (mVideoTask) {
                mVideoInputDone = true;
                mVideoTask.notifyAll();
            }
        }
    }
    /**
     * 读一帧视频数据给解码器
     * @param codec
     * @param extractor
     * @param inputBuffers
     * @param presentationTimeUs
     * @param isAudio
     */
    protected boolean internalProcessInput(final MediaCodec codec, final MediaExtractor extractor, final ByteBuffer[] inputBuffers, final long presentationTimeUs, final boolean isAudio) {
		if (DEBUG) Log.v(TAG, "internalProcessInput:presentationTimeUs=" + presentationTimeUs);
        boolean result = true;
        while (mIsRunning) {
            // 获得一个输入缓存
            final int inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }

            if (inputBufIndex >= 0) {
                // 读数据到intputBuffer
                final int size = extractor.readSampleData(inputBuffers[inputBufIndex], 0);

                if (size > 0) {
                    // 把 buffer数据送入解码器 解码
                    codec.queueInputBuffer(inputBufIndex, 0, size, presentationTimeUs, 0);
                }
                // false 表示没有数据可读
                result = extractor.advance();
                break;
            }
        }
        return result;
    }

    /**
     * 输出一帧数据到 surface
     * @param frameCallback
     */
    private final void handleOutputVideo(final IPlayerListener frameCallback) {
    	if (DEBUG) {
    	    Log.v(TAG, "handleOutputVideo:");
        }
        while (mIsRunning && !mVideoOutputDone) {

            // 获取 解码器输出buffer-有数据，信息在BufferInfo里
            final int outputBufferIndex = mVideoMediaCodec.dequeueOutputBuffer(mVideoBufferInfo, TIMEOUT_USEC);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;

            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                // 输出缓存数组改变，需要使用新的缓存数据
                mVideoOutputBuffers = mVideoMediaCodec.getOutputBuffers();
                if (DEBUG) {
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED:");
                }

            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                // 新的格式
                final MediaFormat newFormat = mVideoMediaCodec.getOutputFormat();
                if (DEBUG) {
                    Log.d(TAG, "video decoder output format changed: " + newFormat);
                }

            } else if (outputBufferIndex < 0) {

                throw new RuntimeException("unexpected result from video decoder.dequeueOutputBuffer: " + outputBufferIndex);

            } else {

                boolean doRender = false;
                if (mVideoBufferInfo.size > 0) {
                    doRender = !internalWriteVideo(mVideoOutputBuffers[outputBufferIndex], 0, mVideoBufferInfo.size, mVideoBufferInfo.presentationTimeUs);

                    if (doRender) {
                        // 调整显示时间，不太懂
                        if (!frameCallback.onFrameAvailable(mVideoBufferInfo.presentationTimeUs))
                            mVideoStartTime = adjustPresentationTime(mVideoSync, mVideoStartTime, mVideoBufferInfo.presentationTimeUs);
                    }
                }

                Log.i(TAG, "onImageAvailable test: releaseOutputBuffer");
                // 释放输出缓存，第二个参数是true会渲染到surface
                mVideoMediaCodec.releaseOutputBuffer(outputBufferIndex, doRender);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // 输出结束
                    if (DEBUG) {
                        Log.d(TAG, "video:output EOS");
                    }
                    synchronized (mVideoTask) {
                        mVideoOutputDone = true;
                        mVideoTask.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * @param buffer
     * @param offset
     * @param size
     * @param presentationTimeUs
     * @return
     */
    protected boolean internalWriteVideo(final ByteBuffer buffer, final int offset, final int size, final long presentationTimeUs) {
		if (DEBUG) Log.v(TAG, "internalWriteVideo");
        return false;
    }

    /**
     * 调整显示时间
     * adjusting frame rate
     * @param sync
     * @param startTime
     * @param presentationTimeUs
     * @return startTime
     */
    protected long adjustPresentationTime(final Object sync, final long startTime, final long presentationTimeUs) {
        if (startTime > 0) {
            for (long t = presentationTimeUs - (System.nanoTime() / 1000 - startTime); t > 0; t = presentationTimeUs - (System.nanoTime() / 1000 - startTime)) {
                synchronized (sync) {
                    try {
                        sync.wait(t / 1000, (int)((t % 1000) * 1000));
                    } catch (final InterruptedException e) {

                    }
                    if ((mPlayerState == REQUEST_STOP) || (mPlayerState == REQUEST_QUIT))
                        break;
                }
            }
            return startTime;
        } else {
            return System.nanoTime() / 1000;
        }
    }

    /**
     * 选择轨道
     * @param extractor
     * @param mimeType
     * @return
     */
    protected static final int selectTrack(final MediaExtractor extractor, final String mimeType) {
        final int numTracks = extractor.getTrackCount();
        MediaFormat format;
        String mime;
        for (int i = 0; i < numTracks; i++) {
            format = extractor.getTrackFormat(i);
            mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(mimeType)) {
                if (DEBUG) {
                    Log.d(TAG_STATIC, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }


    /**
     * 设置地址
     * @param sourcePath
     */
    public void setSourcePath(String sourcePath) {
        this.mSourcePath = sourcePath;
    }

    /**
     * 是否停止
     * @return
     */
    public boolean isStop() {
        return mPlayerState == STATE_STOP;
    }

    /**
     * 是否在播放
     * @return
     */
    public boolean isPlaying() {
        return mPlayerState == STATE_PLAYING;
    }

    /**
     * 是否 暂停状态
     * @return
     */
    public boolean isPaused() {
        return mPlayerState == STATE_PAUSED;
    }

    static class OnImageListenser implements ImageReader.OnImageAvailableListener
    {
        private ArrayList<Image> imageList = new ArrayList<>();

        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Log.i(TAG, "onImageAvailable!!!");

            Image image = null;
            try {
                image = imageReader.acquireLatestImage();

//                mOutputImageWriter.queueInputImage(image);

            }  catch (Exception ex) {
                Log.e(TAG, ex.toString());

                new Thread(() -> {
                    try {
                        Thread.sleep(3000);
                        Log.i(TAG, "onImageAvailable: clear imageList");
                        for (int i = 0; i < imageList.size(); i++) {
                            imageList.get(i).close();
                        }
                        imageList.clear();

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
            }

            if (image != null)
                imageList.add(image);

//            image.close();
        }
    }

}
