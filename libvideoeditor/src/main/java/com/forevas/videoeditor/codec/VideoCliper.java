package com.forevas.videoeditor.codec;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import com.forevas.videoeditor.constants.Constants;
import com.forevas.videoeditor.gpufilter.basefilter.GPUImageFilter;
import com.forevas.videoeditor.gpufilter.helper.MagicFilterFactory;
import com.forevas.videoeditor.gpufilter.helper.MagicFilterType;
import com.forevas.videoeditor.media.VideoInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC;

/**
 * Created by carden
 * no use
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class VideoCliper {
    final int TIMEOUT_USEC = 0;
    private String mInputVideoPath;
    private String mOutputVideoPath;

    MediaCodec videoDecoder;
    MediaCodec videoEncoder;
    MediaCodec audioDecoder;
    MediaCodec audioEncoder;

    MediaExtractor mVideoExtractor;
    MediaExtractor mAudioExtractor;
    MediaMuxer mMediaMuxer;
    static ExecutorService executorService = Executors.newFixedThreadPool(4);
    int muxVideoTrack = -1;
    int muxAudioTrack = -1;
    int videoTrackIndex = -1;
    int audioTrackIndex = -1;
    long startPosition;
    long clipDur;
    int curMode = Constants.MODE_POR_9_16;
    int videoWidth;
    int videoHeight;
    int videoRotation;
    OutputSurface outputSurface = null;
    InputSurface inputSurface = null;
    MediaFormat videoFormat;
    MediaFormat audioFormat;
    GPUImageFilter mFilter;
    boolean videoFinish = false;
    boolean audioFinish = false;
    boolean released = false;
    long before;
    long after;
    Object lock = new Object();
    boolean muxStarted = false;
    OnVideoCutFinishListener listener;

    //先裁剪,再预览,裁剪时的播放器需要自己写
    public VideoCliper() {
        try {
            videoDecoder = MediaCodec.createDecoderByType("video/avc");
            videoEncoder = MediaCodec.createEncoderByType("video/avc");
            audioDecoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setInputVideoPath(String inputPath) {
        mInputVideoPath = inputPath;
        initVideoInfo();
    }

    public void setOutputVideoPath(String outputPath) {
        mOutputVideoPath = outputPath;
    }

    public void setScreenMode(int curMode) {
        this.curMode = curMode;
    }

    public void setOnVideoCutFinishListener(OnVideoCutFinishListener listener) {
        this.listener = listener;
    }

    public void setFilterType(MagicFilterType type) {
        if (type == null || type == MagicFilterType.NONE) {
            mFilter = null;
            return;
        }
        mFilter = MagicFilterFactory.initFilters(type);
    }

    /**
     * 裁剪视频
     *
     * @param startPosition 微秒级
     * @param clipDur       微秒级
     * @throws IOException
     */
    public void clipVideo(long startPosition, long clipDur) throws IOException {
        before = System.currentTimeMillis();
        this.startPosition = startPosition;
        this.clipDur = clipDur;
        mVideoExtractor = new MediaExtractor();
        mAudioExtractor = new MediaExtractor();
        mVideoExtractor.setDataSource(mInputVideoPath);
        mAudioExtractor.setDataSource(mInputVideoPath);
        mMediaMuxer = new MediaMuxer(mOutputVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        //音轨和视轨初始化
        for (int i = 0; i < mVideoExtractor.getTrackCount(); i++) {
            MediaFormat format = mVideoExtractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                videoTrackIndex = i;
                videoFormat = format;
                continue;
            }
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
//                muxAudioTrack = mMediaMuxer.addTrack(format);
                continue;
            }
        }
        executorService.execute(videoCliper);
        executorService.execute(audioCliper);
    }

    private Runnable videoCliper = new Runnable() {
        @Override
        public void run() {
            mVideoExtractor.selectTrack(videoTrackIndex);
            /*ByteBuffer buffer = ByteBuffer.allocate(500 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.presentationTimeUs = 0;*/
            long firstVideoTime = mVideoExtractor.getSampleTime();
            mVideoExtractor.seekTo(firstVideoTime + startPosition, SEEK_TO_PREVIOUS_SYNC);
            /*long curSampleTime = mVideoExtractor.getSampleTime();
            long dis = curSampleTime - firstVideoTime;*/
//            if (Math.abs(dis - startPosition) > 50000 || mFilter != null||!attemptAdjustSize()) {//误差大于50ms,加滤镜,尺寸需要裁剪
            initVideoCodec();//暂时统一处理,为音频转换采样率做准备
            startVideoCodec(videoDecoder, videoEncoder, mVideoExtractor, inputSurface, outputSurface, firstVideoTime, startPosition, clipDur);
            /*} else {
                startMux(videoFormat);
                while (true) {
                    int readSampleData = mVideoExtractor.readSampleData(buffer, 0);
                    if (readSampleData < 0) {
                        break;
                    }
                    info.offset = 0;
                    info.size = readSampleData;
                    info.flags = mVideoExtractor.getSampleFlags() == 0 ? 0 : MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    info.presentationTimeUs = mVideoExtractor.getSampleTime();
                    if (info.presentationTimeUs - firstVideoTime - startPosition > clipDur) {
                        break;
                    }
                    mMediaMuxer.writeSampleData(muxVideoTrack, buffer, info);
                    mVideoExtractor.advance();
                }
            }*/
            videoFinish = true;
            release();
        }
    };
    /*private Runnable audioCliper = new Runnable() {
        @Override
        public void run() {
            ByteBuffer buffer = ByteBuffer.allocate(500 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.presentationTimeUs = 0;
            mAudioExtractor.selectTrack(audioTrackIndex);
            long firstAudeoTime = mAudioExtractor.getSampleTime();
            mAudioExtractor.seekTo(startPosition, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            synchronized (lock) {
                if (!muxStarted) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            while (true) {
                int readSampleData = mAudioExtractor.readSampleData(buffer, 0);
                if (readSampleData < 0) {
                    break;
                }
                info.offset = 0;
                info.size = readSampleData;
                info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                info.presentationTimeUs = mAudioExtractor.getSampleTime();
                mMediaMuxer.writeSampleData(muxAudioTrack, buffer, info);
                if (info.presentationTimeUs - firstAudeoTime - startPosition > clipDur) {
                    break;
                }
                mAudioExtractor.advance();
            }
            audioFinish = true;
            release();
        }
    };*/
    private Runnable audioCliper = new Runnable() {
        @Override
        public void run() {
            mAudioExtractor.selectTrack(audioTrackIndex);
            initAudioCodec();
            startAudioCodec(audioDecoder, audioEncoder, mAudioExtractor, mAudioExtractor.getSampleTime(), startPosition, clipDur);
            audioFinish = true;
            release();
        }
    };

    private void initVideoInfo() {
        MediaMetadataRetriever retr = new MediaMetadataRetriever();
        retr.setDataSource(mInputVideoPath);
        String width = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String height = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String rotation = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        videoWidth = Integer.parseInt(width);
        videoHeight = Integer.parseInt(height);
        videoRotation = Integer.parseInt(rotation);
    }

    private boolean attemptAdjustSize() {
        float tempW, tempH;
        if (videoRotation == 0 || videoRotation == 180) {
            tempW = videoWidth;
            tempH = videoHeight;
        } else {
            tempW = videoHeight;
            tempH = videoWidth;
        }
        switch (curMode) {
            case Constants.MODE_POR_9_16:
                if (tempW / tempH != 9f / 16) {
                    return false;
                }
                break;
            case Constants.MODE_POR_1_1:
                if (tempW != tempH) {
                    return false;
                }
                break;
            case Constants.MODE_POR_16_9:
                if (tempW / tempH != 16f / 9) {
                    return false;
                }
                break;
        }
        return true;
    }

    private void initAudioCodec() {
        audioDecoder.configure(audioFormat, null, null, 0);
        audioDecoder.start();
        /*int channelCount;
        try{
            channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }catch (Exception e){
            channelCount=2;
        }*/

        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, /*channelCount*/2);//这里一定要注意声道的问题
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);//比特率
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private void startAudioCodec(MediaCodec decoder, MediaCodec encoder, MediaExtractor extractor, long firstSampleTime, long startPosition, long duration) {
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
        boolean done = false;//用于判断整个编解码过程是否结束
        boolean inputDone = false;
        boolean decodeDone = false;
        extractor.seekTo(firstSampleTime + startPosition, SEEK_TO_PREVIOUS_SYNC);
        int decodeinput=0;
        int encodeinput=0;
        int encodeoutput=0;
        long lastEncodeOutputTimeStamp=-1;
        while (!done) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoderInputBuffers[inputIndex];
                    inputBuffer.clear();
                    int readSampleData = extractor.readSampleData(inputBuffer, 0);
                    long dur = extractor.getSampleTime() - firstSampleTime - startPosition;
                    if ((dur < duration) && readSampleData > 0) {
                        decoder.queueInputBuffer(inputIndex, 0, readSampleData, extractor.getSampleTime(), 0);
                        decodeinput++;
                        System.out.println("videoCliper audio decodeinput"+decodeinput+" dataSize"+readSampleData+" sampeTime"+extractor.getSampleTime());
                        extractor.advance();
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        System.out.println("videoCliper audio decodeInput end");
                        inputDone = true;
                    }
                }
            }
            if (!decodeDone) {
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    decoderOutputBuffers = decoder.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // expected before first buffer of data
                    MediaFormat newFormat = decoder.getOutputFormat();
                } else if (index < 0) {
                } else {
                    boolean canEncode = (info.size != 0 && info.presentationTimeUs - firstSampleTime > startPosition);
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (canEncode&&!endOfStream) {
                        ByteBuffer decoderOutputBuffer = decoderOutputBuffers[index];

                        int encodeInputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                        if(encodeInputIndex>=0){
                            ByteBuffer encoderInputBuffer = encoderInputBuffers[encodeInputIndex];
                            encoderInputBuffer.clear();
                            if (info.size < 4096) {//这里看起来应该是16位单声道转16位双声道
                                byte[] chunkPCM = new byte[info.size];
                                decoderOutputBuffer.get(chunkPCM);
                                decoderOutputBuffer.clear();
                                //说明是单声道的,需要转换一下
                                byte[] stereoBytes = new byte[info.size * 2];
                                for (int i = 0; i < info.size; i += 2) {
                                    stereoBytes[i * 2 + 0] = chunkPCM[i];
                                    stereoBytes[i * 2 + 1] = chunkPCM[i + 1];
                                    stereoBytes[i * 2 + 2] = chunkPCM[i];
                                    stereoBytes[i * 2 + 3] = chunkPCM[i + 1];
                                }
                                try{
                                    encoderInputBuffer.put(stereoBytes);
                                    encoder.queueInputBuffer(encodeInputIndex, 0, stereoBytes.length, info.presentationTimeUs, 0);
                                    encodeinput++;
                                    System.out.println("videoCliper audio encodeInput"+encodeinput+" dataSize"+info.size+" sampeTime"+info.presentationTimeUs);
                                }catch (Exception e){
                                    e.printStackTrace();
//                                    Log.e("VideoCliper-->",e);
                                }

                            }else{
                                try {
                                    encoderInputBuffer.put(decoderOutputBuffer);
                                    encoder.queueInputBuffer(encodeInputIndex, info.offset, info.size, info.presentationTimeUs, 0);
                                    encodeinput++;
                                    System.out.println("videoCliper audio encodeInput"+encodeinput+" dataSize"+info.size+" sampeTime"+info.presentationTimeUs);
                                }catch (Exception e){
                                    e.printStackTrace();
                                }

                            }
                        }
                    }
                    if(endOfStream){
                        int encodeInputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                        encoder.queueInputBuffer(encodeInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        System.out.println("videoCliper audio encodeInput end");
                        decodeDone = true;
                    }
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            boolean encoderOutputAvailable = true;
            while (encoderOutputAvailable) {
                int encoderStatus = encoder.dequeueOutputBuffer(outputInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    encoderOutputAvailable = false;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    startMux(newFormat, 1);
                } else if (encoderStatus < 0) {
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    done = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (done) {
                        encoderOutputAvailable = false;
                    }
                    // Write the data to the output "file".
                    if (outputInfo.presentationTimeUs == 0 && !done) {
                        continue;
                    }
                    if (outputInfo.size != 0&&outputInfo.presentationTimeUs>0) {
                        /*encodedData.position(outputInfo.offset);
                        encodedData.limit(outputInfo.offset + outputInfo.size);*/
                        if(!muxStarted){
                            synchronized (lock){
                                if(!muxStarted){
                                    try {
                                        lock.wait();
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                        if(outputInfo.presentationTimeUs>lastEncodeOutputTimeStamp){//为了避免有问题的数据
                            encodeoutput++;
                            System.out.println("videoCliper audio encodeOutput"+encodeoutput+" dataSize"+outputInfo.size+" sampeTime"+outputInfo.presentationTimeUs);
                            mMediaMuxer.writeSampleData(muxAudioTrack, encodedData, outputInfo);
                            lastEncodeOutputTimeStamp=outputInfo.presentationTimeUs;
                        }
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue;
                }
            }
        }
    }

    private void initVideoCodec() {
        int encodeW = 0;
        int encodeH = 0;
        switch (curMode) {
            case Constants.MODE_POR_9_16:
                encodeW = Constants.mode_por_encode_width_9_16;
                encodeH = Constants.mode_por_encode_height_9_16;
                break;
            case Constants.MODE_POR_1_1:
                encodeW = Constants.mode_por_encode_width_1_1;
                encodeH = Constants.mode_por_encode_height_1_1;
                break;
            case Constants.MODE_POR_16_9:
                encodeW = Constants.mode_por_encode_width_16_9;
                encodeH = Constants.mode_por_encode_height_16_9;
                break;
        }
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", encodeW, encodeH);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 3000000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        videoEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = new InputSurface(videoEncoder.createInputSurface());
        inputSurface.makeCurrent();
        videoEncoder.start();


        VideoInfo info = new VideoInfo();
        info.width = videoWidth;
        info.height = videoHeight;
        info.rotation = videoRotation;
        outputSurface = new OutputSurface(info, curMode);
        if (mFilter != null) {
            outputSurface.addGpuFilter(mFilter);
        }
        videoDecoder.configure(videoFormat, outputSurface.getSurface(), null, 0);
        videoDecoder.start();//解码器启动
    }

    /**
     * 将两个关键帧之间截取的部分重新编码
     *
     * @param decoder
     * @param encoder
     * @param extractor
     * @param inputSurface
     * @param outputSurface
     * @param firstSampleTime 视频第一帧的时间戳
     * @param startPosition   微秒级
     * @param duration        微秒级
     */
    private void startVideoCodec(MediaCodec decoder, MediaCodec encoder, MediaExtractor extractor, InputSurface inputSurface, OutputSurface outputSurface, long firstSampleTime, long startPosition, long duration) {
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
        boolean done = false;//用于判断整个编解码过程是否结束
        boolean inputDone = false;
        boolean decodeDone = false;
        while (!done) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoderInputBuffers[inputIndex];
                    inputBuffer.clear();
                    int readSampleData = extractor.readSampleData(inputBuffer, 0);
                    long dur = extractor.getSampleTime() - firstSampleTime - startPosition;//当前已经截取的视频长度
                    if ((dur < duration) && readSampleData > 0) {
                        decoder.queueInputBuffer(inputIndex, 0, readSampleData, extractor.getSampleTime(), 0);
                        extractor.advance();
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    }
                }
            }
            if (!decodeDone) {
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    //decoderOutputBuffers = decoder.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // expected before first buffer of data
                    MediaFormat newFormat = decoder.getOutputFormat();
                } else if (index < 0) {
                } else {
                    boolean doRender = (info.size != 0 && info.presentationTimeUs - firstSampleTime > startPosition);
                    decoder.releaseOutputBuffer(index, doRender);
                    if (doRender) {
                        // This waits for the image and renders it after it arrives.
                        outputSurface.awaitNewImage();
                        outputSurface.drawImage();
                        // Send it to the encoder.
                        inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                        inputSurface.swapBuffers();
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                        decodeDone = true;
                    }
                }
            }
            boolean encoderOutputAvailable = true;
            while (encoderOutputAvailable) {
                int encoderStatus = encoder.dequeueOutputBuffer(outputInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    encoderOutputAvailable = false;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    startMux(newFormat, 0);
                } else if (encoderStatus < 0) {
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    done = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (done) {
                        encoderOutputAvailable = false;
                    }
                    // Write the data to the output "file".
                    if (outputInfo.presentationTimeUs == 0 && !done) {
                        continue;
                    }
                    if (outputInfo.size != 0) {
                        encodedData.position(outputInfo.offset);
                        encodedData.limit(outputInfo.offset + outputInfo.size);
                        if(!muxStarted){
                            synchronized (lock){
                                if(!muxStarted){
                                    try {
                                        lock.wait();
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                        mMediaMuxer.writeSampleData(muxVideoTrack, encodedData, outputInfo);
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue;
                }
            }
        }
    }

    /**
     * @param mediaFormat
     * @param flag        0 video,1 audio
     */
    private void startMux(MediaFormat mediaFormat, int flag) {
        if (flag == 0) {
            muxVideoTrack = mMediaMuxer.addTrack(mediaFormat);
        } else if (flag == 1) {
            muxAudioTrack = mMediaMuxer.addTrack(mediaFormat);
        }
        synchronized (lock) {
            if (muxAudioTrack != -1 && muxVideoTrack != -1 && !muxStarted) {
                mMediaMuxer.start();
                muxStarted = true;
                lock.notify();
            }
        }
    }

    private synchronized void release() {
        if (!videoFinish || !audioFinish || released) {
            return;
        }
        mVideoExtractor.release();
        mAudioExtractor.release();
        mMediaMuxer.stop();
        mMediaMuxer.release();
        if (outputSurface != null) {
            outputSurface.release();
        }
        if (inputSurface != null) {
            inputSurface.release();
        }
        videoDecoder.stop();
        videoDecoder.release();
        videoEncoder.stop();
        videoEncoder.release();
        audioDecoder.stop();
        audioDecoder.release();
        audioEncoder.stop();
        audioEncoder.release();
        released = true;
        after = System.currentTimeMillis();
        System.out.println("cutVideo count1=" + (after - before));
        if (listener != null) {
            listener.onFinish();
        }
    }

    public interface OnVideoCutFinishListener {
        void onFinish();
    }
}
