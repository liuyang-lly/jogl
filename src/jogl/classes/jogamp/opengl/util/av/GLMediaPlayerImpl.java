/**
 * Copyright 2012 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 * 
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package jogamp.opengl.util.av;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;

import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.media.opengl.GLContext;
import javax.media.opengl.GLDrawable;
import javax.media.opengl.GLDrawableFactory;
import javax.media.opengl.GLES2;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;

import jogamp.opengl.Debug;

import com.jogamp.common.os.Platform;
import com.jogamp.common.util.LFRingbuffer;
import com.jogamp.common.util.Ringbuffer;
import com.jogamp.opengl.util.TimeFrameI;
import com.jogamp.opengl.util.av.AudioSink;
import com.jogamp.opengl.util.av.GLMediaPlayer;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureSequence;
import com.jogamp.opengl.util.texture.TextureSequence.TextureFrame;

/**
 * After object creation an implementation may customize the behavior:
 * <ul>
 *   <li>{@link #setDesTextureCount(int)}</li>
 *   <li>{@link #setTextureTarget(int)}</li>
 *   <li>{@link EGLMediaPlayerImpl#setEGLTexImageAttribs(boolean, boolean)}.</li>
 * </ul>
 * 
 * <p>
 * See {@link GLMediaPlayer}.
 * </p>
 */
public abstract class GLMediaPlayerImpl implements GLMediaPlayer {
    private static final int STREAM_WORKER_DELAY = Debug.getIntProperty("jogl.debug.GLMediaPlayer.StreamWorker.delay", false, 0);

    protected static final String unknown = "unknown";

    protected volatile State state;
    private Object stateLock = new Object();
    
    protected int textureCount;
    protected int textureTarget;
    protected int textureFormat;
    protected int textureInternalFormat; 
    protected int textureType;
    protected int texUnit;
    
    
    protected int[] texMinMagFilter = { GL.GL_NEAREST, GL.GL_NEAREST };
    protected int[] texWrapST = { GL.GL_CLAMP_TO_EDGE, GL.GL_CLAMP_TO_EDGE };
    
    protected URI streamLoc = null;
    
    protected volatile float playSpeed = 1.0f;
    protected float audioVolume = 1.0f;
    
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int vid = GLMediaPlayer.STREAM_ID_AUTO;
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int aid = GLMediaPlayer.STREAM_ID_AUTO;
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int width = 0;
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int height = 0;
    /** Video avg. fps. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected float fps = 0;
    /** Video avg. frame duration in ms. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected float frame_duration = 0f;
    /** Stream bps. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int bps_stream = 0;
    /** Video bps. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int bps_video = 0;
    /** Audio bps. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int bps_audio = 0;
    /** In frames. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int videoFrames = 0;
    /** In frames. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int audioFrames = 0;
    /** In ms. Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected int duration = 0;
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected String acodec = unknown;
    /** Shall be set by the {@link #initStreamImpl(int, int)} method implementation. */
    protected String vcodec = unknown;
    
    protected volatile int decodedFrameCount = 0;
    protected int presentedFrameCount = 0;
    protected int displayedFrameCount = 0;
    protected volatile int video_pts_last = 0;
    
    /** See {@link #getAudioSink()}. Set by implementation if used from within {@link #initStreamImpl(int, int)}! */
    protected AudioSink audioSink = null;
    protected boolean audioSinkPlaySpeedSet = false;
    
    /** System Clock Reference (SCR) of first audio PTS at start time. */
    private long audio_scr_t0 = 0;
    private boolean audioSCR_reset = true;
    
    /** System Clock Reference (SCR) of first video frame at start time. */
    private long video_scr_t0 = 0;
    /** System Clock Reference (SCR) PTS offset, i.e. first video PTS at start time. */
    private int video_scr_pts = 0;
    /** Cumulative video pts diff. */
    private float video_dpts_cum = 0;
    /** Cumulative video frames. */
    private int video_dpts_count = 0;
    /** Number of min frame count required for video cumulative sync. */ 
    private static final int VIDEO_DPTS_NUM = 20;
    /** Cumulative coefficient, value {@value}. */
    private static final float VIDEO_DPTS_COEFF = 0.7943282f; // (float) Math.exp(Math.log(0.01) / VIDEO_DPTS_NUM);
    /** Maximum valid video pts diff. */
    private static final int VIDEO_DPTS_MAX = 5000; // 5s max diff
    /** Trigger video PTS reset with given cause as bitfield. */
    private boolean videoSCR_reset = false;
    
    protected TextureFrame[] videoFramesOrig = null;
    protected Ringbuffer<TextureFrame> videoFramesFree =  null;
    protected Ringbuffer<TextureFrame> videoFramesDecoded =  null;
    protected volatile TextureFrame lastFrame = null;

    private ArrayList<GLMediaEventListener> eventListeners = new ArrayList<GLMediaEventListener>();

    protected GLMediaPlayerImpl() {
        this.textureCount=0;
        this.textureTarget=GL.GL_TEXTURE_2D;
        this.textureFormat = GL.GL_RGBA;
        this.textureInternalFormat = GL.GL_RGBA;
        this.textureType = GL.GL_UNSIGNED_BYTE;        
        this.texUnit = 0;
        this.state = State.Uninitialized;
    }

    @Override
    public final void setTextureUnit(int u) { texUnit = u; }
    
    @Override
    public final int getTextureUnit() { return texUnit; }
    
    @Override
    public final int getTextureTarget() { return textureTarget; }
    
    @Override
    public final int getTextureCount() { return textureCount; }
    
    protected final void setTextureTarget(int target) { textureTarget=target; }
    protected final void setTextureFormat(int internalFormat, int format) { 
        textureInternalFormat=internalFormat; 
        textureFormat=format; 
    }    
    protected final void setTextureType(int t) { textureType=t; }

    public final void setTextureMinMagFilter(int[] minMagFilter) { texMinMagFilter[0] = minMagFilter[0]; texMinMagFilter[1] = minMagFilter[1];}
    public final int[] getTextureMinMagFilter() { return texMinMagFilter; }
    
    public final void setTextureWrapST(int[] wrapST) { texWrapST[0] = wrapST[0]; texWrapST[1] = wrapST[1];}
    public final int[] getTextureWrapST() { return texWrapST; }    
    
    private final void checkStreamInit() {
        if(State.Uninitialized == state ) {
            throw new IllegalStateException("Stream not initialized: "+this);
        }        
    }
    
    private final void checkGLInit() {
        if(State.Uninitialized == state || State.Initialized == state ) {
            throw new IllegalStateException("GL not initialized: "+this);
        }        
    }
    
    @Override
    public String getRequiredExtensionsShaderStub() throws IllegalStateException {
        checkGLInit();
        if(GLES2.GL_TEXTURE_EXTERNAL_OES == textureTarget) {
            return TextureSequence.GL_OES_EGL_image_external_Required_Prelude;
        }
        return "";
    }
        
    @Override
    public String getTextureSampler2DType() throws IllegalStateException {
        checkGLInit();
        switch(textureTarget) {
            case GL.GL_TEXTURE_2D:
            case GL2.GL_TEXTURE_RECTANGLE: 
                return TextureSequence.sampler2D;
            case GLES2.GL_TEXTURE_EXTERNAL_OES:
                return TextureSequence.samplerExternalOES;
            default:
                throw new GLException("Unsuported texture target: "+toHexString(textureTarget));            
        }
    }
    
    /**
     * {@inheritDoc}
     * 
     * This implementation simply returns the build-in function name of <code>texture2D</code>,
     * if not overridden by specialization.
     */
    @Override
    public String getTextureLookupFunctionName(String desiredFuncName) throws IllegalStateException {
        checkGLInit();
        return "texture2D";
    }
    
    /**
     * {@inheritDoc}
     * 
     * This implementation simply returns an empty string since it's using 
     * the build-in function <code>texture2D</code>,
     * if not overridden by specialization.
     */
    @Override
    public String getTextureLookupFragmentShaderImpl() throws IllegalStateException {
        checkGLInit();
        return ""; 
    }
    
    @Override
    public final int getDecodedFrameCount() { return decodedFrameCount; }
    
    @Override
    public final int getPresentedFrameCount() { return presentedFrameCount; }
    
    @Override
    public final int getVideoPTS() { return video_pts_last; }
    
    @Override
    public final int getAudioPTS() { 
        if( State.Uninitialized != state ) {
            return getAudioPTSImpl();
        }
        return 0;
    }
    /** Override if not using audioSink! */
    protected int getAudioPTSImpl() {
        if( null != audioSink ) {
            return audioSink.getPTS();
        } else {
            return 0;
        }
    }
    
    @Override
    public final State getState() { return state; }
    
    @Override
    public final State play() {
        synchronized( stateLock ) {
            final State preState = state;
            switch( state ) {
                case Paused:
                    if( playImpl() ) {
                        resetAudioVideoPTS();
                        if( null != audioSink ) {
                            audioSink.play(); // cont. w/ new data
                        }
                        streamWorker.doResume();
                        changeState(0, State.Playing);
                    }
                default:
            }
            if(DEBUG) { System.err.println("Play: "+preState+" -> "+state+", "+toString()); }
            return state;
        }
    }
    protected abstract boolean playImpl();
    
    @Override
    public final State pause() {
        return pauseImpl(0);
    }
    private final State pauseImpl(int event_mask) {
        synchronized( stateLock ) {
            final State preState = state;
            if( State.Playing == state ) {
                event_mask = addStateEventMask(event_mask, GLMediaPlayer.State.Paused);
                state = State.Paused;
                streamWorker.doPause();
                if( null != audioSink ) {
                    audioSink.pause();
                }
                attributesUpdated( event_mask );
                if( !pauseImpl() ) {
                    play();
                }
            }
            if(DEBUG) { System.err.println("Pause: "+preState+" -> "+state+", "+toString()); }
            return state;
        }
    }
    protected abstract boolean pauseImpl();
    
    @Override
    public final int seek(int msec) {
        synchronized( stateLock ) {
            final State preState = state;
            final int pts1;
            switch(state) {
                case Playing:
                case Paused:
                    final State _state = state;
                    state = State.Paused;
                    streamWorker.doPause();
                    pts1 = seekImpl(msec);
                    resetAllAudioVideoSync();
                    if( null != audioSink && State.Playing == _state ) {
                        audioSink.play(); // cont. w/ new data
                    }
                    System.err.println("SEEK XXX: "+getPerfString());
                    streamWorker.doResume();
                    state = _state;
                    break;
                default:
                    pts1 = 0;
            }
            if(DEBUG) { System.err.println("Seek("+msec+"): "+preState+" -> "+state+", "+toString()); }
            return pts1;
        }
    }
    protected abstract int seekImpl(int msec);
    
    @Override
    public final float getPlaySpeed() {
        return playSpeed;
    }
    
    @Override
    public final boolean setPlaySpeed(float rate) {
        synchronized( stateLock ) {
            final float preSpeed = playSpeed;
            boolean res = false;
            if(State.Uninitialized != state ) {
                if( rate > 0.01f ) {
                    if( Math.abs(1.0f - rate) < 0.01f ) {
                        rate = 1.0f;
                    }
                    if( setPlaySpeedImpl(rate) ) {
                        resetAudioVideoPTS();
                        playSpeed = rate;
                        res = true;
                    }
                }
            }
            if(DEBUG) { System.err.println("setPlaySpeed("+rate+"): "+state+", "+preSpeed+" -> "+playSpeed+", "+toString()); }
            return res;
        }
    }
    /** 
     * Override if not using AudioSink, or AudioSink's {@link AudioSink#setPlaySpeed(float)} is not sufficient!
     * <p>
     * AudioSink shall respect <code>!audioSinkPlaySpeedSet</code> to determine data_size 
     * at {@link AudioSink#enqueueData(com.jogamp.opengl.util.av.AudioSink.AudioFrame)}.
     * </p> 
     */
    protected boolean setPlaySpeedImpl(float rate) {
        if( null != audioSink ) {
            audioSinkPlaySpeedSet = audioSink.setPlaySpeed(rate);
        }
        // still true, even if audioSink rejects command since we deal w/ video sync 
        // and AudioSink w/ audioSinkPlaySpeedSet at enqueueData(..).
        return true;
    }

    @Override
    public final float getAudioVolume() {
        getAudioVolumeImpl();
        return audioVolume;
    }
    /** 
     * Override if not using AudioSink, or AudioSink's {@link AudioSink#getVolume()} is not sufficient!
     */
    protected void getAudioVolumeImpl() {
        if( null != audioSink ) {
            audioVolume = audioSink.getVolume();
        }
    }
    
    @Override
    public boolean setAudioVolume(float v) {
        synchronized( stateLock ) {
            final float preVolume = audioVolume;
            boolean res = false;
            if(State.Uninitialized != state ) {
                if( Math.abs(v) < 0.01f ) {
                    v = 0.0f;
                } else if( Math.abs(1.0f - v) < 0.01f ) {
                    v = 1.0f;
                }
                if( setAudioVolumeImpl(v) ) {
                    audioVolume = v;
                    res = true;
                }
            }
            if(DEBUG) { System.err.println("setAudioVolume("+v+"): "+state+", "+preVolume+" -> "+audioVolume+", "+toString()); }
            return res;
        }
    }
    /** 
     * Override if not using AudioSink, or AudioSink's {@link AudioSink#setVolume(float)} is not sufficient!
     */
    protected boolean setAudioVolumeImpl(float v) {
        if( null != audioSink ) {
            return audioSink.setVolume(v);
        }
        // still true, even if audioSink rejects command ..
        return true;
    }
    
    @Override
    public final void initStream(URI streamLoc, int vid, int aid, int reqTextureCount) throws IllegalStateException, IllegalArgumentException {
        synchronized( stateLock ) {
            if(State.Uninitialized != state) {
                throw new IllegalStateException("Instance not unintialized: "+this);
            }
            if(null == streamLoc) {
                throw new IllegalArgumentException("streamLock is null");
            }
            if( STREAM_ID_NONE != vid ) {
                textureCount = validateTextureCount(reqTextureCount);
                if( textureCount < 2 ) {
                    throw new InternalError("Validated texture count < 2: "+textureCount);
                }
            } else {
                textureCount = 0;
            }
            decodedFrameCount = 0;
            presentedFrameCount = 0;
            displayedFrameCount = 0;            
            this.streamLoc = streamLoc;
            this.vid = vid;
            this.aid = aid;
            if (this.streamLoc != null) {
                streamWorker = new StreamWorker();
            }
        }
    }
    /**
     * Implementation shall set the following set of data here
     * @see #vid
     * @see #aid 
     * @see #width
     * @see #height
     * @see #fps
     * @see #bps_stream
     * @see #videoFrames
     * @see #audioFrames
     * @see #acodec
     * @see #vcodec
    */
    protected abstract void initStreamImpl(int vid, int aid) throws Exception;
    
    @Override
    public final StreamException getStreamException() {
        synchronized( stateLock ) {
            if( null != streamWorker ) {
                return streamWorker.getStreamErr();
            } else {
                return null;
            }
        }
    }
    
    @Override
    public final void initGL(GL gl) throws IllegalStateException, StreamException, GLException {
        synchronized( stateLock ) {
            checkStreamInit();
            final StreamException streamInitErr = streamWorker.getStreamErr();
            if( null != streamInitErr ) {
                streamWorker = null;
                destroy(null);
                throw streamInitErr;
            }
            try {                
                if( STREAM_ID_NONE != vid ) {
                    removeAllTextureFrames(gl);
                    initGLImpl(gl);
                    videoFramesOrig = createTexFrames(gl, textureCount);
                    videoFramesFree = new LFRingbuffer<TextureFrame>(videoFramesOrig);
                    videoFramesDecoded = new LFRingbuffer<TextureFrame>(TextureFrame[].class, textureCount);
                    lastFrame = videoFramesFree.getBlocking( );
                    streamWorker.initGL(gl);
                } else {
                    removeAllTextureFrames(null);
                    initGLImpl(null);
                    setTextureFormat(-1, -1);
                    setTextureType(-1);
                    videoFramesOrig = null;
                    videoFramesFree = null;
                    videoFramesDecoded = null;
                    lastFrame = null;
                }
                changeState(0, State.Paused);
            } catch (Throwable t) {
                throw new GLException("Error initializing GL resources", t);
            }
        }
    }
    /** 
     * Shall initialize all GL related resources, if not audio-only.
     * <p>
     * Shall also take care of {@link AudioSink} initialization if appropriate.
     * </p>
     * @param gl null for audio-only, otherwise a valid and current GL object.
     * @throws IOException
     * @throws GLException
     */
    protected abstract void initGLImpl(GL gl) throws IOException, GLException;
    
    /** 
     * Returns the validated number of textures to be handled.
     * <p>
     * Default is {@link #TEXTURE_COUNT_MIN} minimum textures.
     * </p>
     * <p>
     * Implementation must at least return a texture count of <i>two</i>, the last texture and the decoding texture.
     * </p>
     */
    protected int validateTextureCount(int desiredTextureCount) {
        return desiredTextureCount < TEXTURE_COUNT_MIN ? TEXTURE_COUNT_MIN : desiredTextureCount;
    }
    
    protected TextureFrame[] createTexFrames(GL gl, final int count) {
        final int[] texNames = new int[count];
        gl.glGenTextures(count, texNames, 0);
        final int err = gl.glGetError();
        if( GL.GL_NO_ERROR != err ) {
            throw new RuntimeException("TextureNames creation failed (num: "+count+"): err "+toHexString(err));
        }
        final TextureFrame[] texFrames = new TextureFrame[count];
        for(int i=0; i<count; i++) {
            texFrames[i] = createTexImage(gl, texNames[i]);
        }
        return texFrames;
    }
    protected abstract TextureFrame createTexImage(GL gl, int texName);
    
    protected final Texture createTexImageImpl(GL gl, int texName, int tWidth, int tHeight, boolean mustFlipVertically) {
        if( 0 > texName ) {
            throw new RuntimeException("TextureName "+toHexString(texName)+" invalid.");
        }
        gl.glActiveTexture(GL.GL_TEXTURE0+getTextureUnit());
        gl.glBindTexture(textureTarget, texName);
        {
            final int err = gl.glGetError();
            if( GL.GL_NO_ERROR != err ) {
                throw new RuntimeException("Couldn't bind textureName "+toHexString(texName)+" to 2D target, err "+toHexString(err));
            }
        }

        if(GLES2.GL_TEXTURE_EXTERNAL_OES != textureTarget) {
            // create space for buffer with a texture
            gl.glTexImage2D(
                    textureTarget,    // target
                    0,                // level
                    textureInternalFormat, // internal format
                    tWidth,           // width
                    tHeight,          // height
                    0,                // border
                    textureFormat,
                    textureType,
                    null);            // pixels -- will be provided later
            {
                final int err = gl.glGetError();
                if( GL.GL_NO_ERROR != err ) {
                    throw new RuntimeException("Couldn't create TexImage2D RGBA "+tWidth+"x"+tHeight+", err "+toHexString(err));
                }
            }
            if(DEBUG) {
                System.err.println("Created TexImage2D RGBA "+tWidth+"x"+tHeight+", target "+toHexString(textureTarget)+
                                   ", ifmt "+toHexString(GL.GL_RGBA)+", fmt "+toHexString(textureFormat)+", type "+toHexString(textureType));
            }
        }
        gl.glTexParameteri(textureTarget, GL.GL_TEXTURE_MIN_FILTER, texMinMagFilter[0]);
        gl.glTexParameteri(textureTarget, GL.GL_TEXTURE_MAG_FILTER, texMinMagFilter[1]);        
        gl.glTexParameteri(textureTarget, GL.GL_TEXTURE_WRAP_S, texWrapST[0]);
        gl.glTexParameteri(textureTarget, GL.GL_TEXTURE_WRAP_T, texWrapST[1]);
        
        return com.jogamp.opengl.util.texture.TextureIO.newTexture(
                     texName, textureTarget,
                     tWidth, tHeight,
                     width,  height,
                     mustFlipVertically);        
    }
        
    protected void destroyTexFrame(GL gl, TextureFrame frame) {
        frame.getTexture().destroy(gl);        
    }

    @Override
    public final TextureFrame getLastTexture() throws IllegalStateException {
        if( State.Paused != state && State.Playing != state ) {
            throw new IllegalStateException("Instance not paused or playing: "+this);
        }
        return lastFrame;
    }
    
    private final void removeAllTextureFrames(GL gl) {
        final TextureFrame[] texFrames = videoFramesOrig;
        videoFramesOrig = null;
        videoFramesFree = null;
        videoFramesDecoded = null;
        lastFrame = null;
        if( null != texFrames ) {
            for(int i=0; i<texFrames.length; i++) {
                final TextureFrame frame = texFrames[i];
                if(null != frame) {
                    if( null != gl ) {
                        destroyTexFrame(gl, frame);
                    }
                    texFrames[i] = null;
                }
                System.err.println(Thread.currentThread().getName()+"> Clear TexFrame["+i+"]: "+frame+" -> null");            
            }        
        }
    }
    
    protected TextureFrame cachedFrame = null;
    protected long lastTimeMillis = 0;
    
    @Override
    public final TextureFrame getNextTexture(GL gl) throws IllegalStateException {
        synchronized( stateLock ) {
            if( State.Paused != state && State.Playing != state ) {
                throw new IllegalStateException("Instance not paused or playing: "+this);
            }
            if(State.Playing == state) {
                TextureFrame nextFrame = null;
                boolean dropFrame = false;
                try {
                    do {
                        final long currentTimeMillis;
                        final boolean playCached = null != cachedFrame;
                        final boolean droppedFrame;
                        if( dropFrame ) {
                            presentedFrameCount--;
                            dropFrame = false;
                            droppedFrame = true;
                        } else {
                            droppedFrame = false;
                        }
                        if( playCached ) {
                            nextFrame = cachedFrame;
                            cachedFrame = null;
                            presentedFrameCount--;
                        } else if( STREAM_ID_NONE != vid ) {
                            nextFrame = videoFramesDecoded.get();
                        }
                        currentTimeMillis = Platform.currentTimeMillis();
                        if( null != nextFrame ) {
                            presentedFrameCount++;
                            final int video_pts = nextFrame.getPTS();
                            if( video_pts == TimeFrameI.END_OF_STREAM_PTS ) {
                                pauseImpl(GLMediaEventListener.EVENT_CHANGE_EOS);
                            } else if( video_pts != TimeFrameI.INVALID_PTS ) {
                                final int audio_pts = getAudioPTSImpl();
                                final int audio_scr = (int) ( ( currentTimeMillis - audio_scr_t0 ) * playSpeed );
                                final int d_apts;
                                if( audio_pts != TimeFrameI.INVALID_PTS ) {
                                    d_apts = audio_pts - audio_scr;
                                } else {
                                    d_apts = 0;
                                }
                                
                                final int frame_period_last = video_pts - video_pts_last; // rendering loop interrupted ?
                                if( videoSCR_reset || frame_period_last > frame_duration*10 ) {
                                    videoSCR_reset = false;
                                    video_scr_t0 = currentTimeMillis;
                                    video_scr_pts = video_pts;
                                }
                                final int video_scr = video_scr_pts + (int) ( ( currentTimeMillis - video_scr_t0 ) * playSpeed );
                                final int d_vpts = video_pts - video_scr;
                                // final int d_avpts = d_vpts - d_apts;
                                if( -VIDEO_DPTS_MAX > d_vpts || d_vpts > VIDEO_DPTS_MAX ) {
                                // if( -VIDEO_DPTS_MAX > d_avpts || d_avpts > VIDEO_DPTS_MAX ) {
                                    if( DEBUG ) {
                                        System.err.println( "AV*: dT "+(currentTimeMillis-lastTimeMillis)+", "+
                                                getPerfStringImpl( video_scr, video_pts, d_vpts, audio_scr, audio_pts, d_apts, 0 ) + ", "+nextFrame+", playCached " + playCached+ ", dropFrame "+dropFrame);
                                    }
                                } else {
                                    final int dpy_den = displayedFrameCount > 0 ? displayedFrameCount : 1;
                                    final int avg_dpy_duration = ( (int) ( currentTimeMillis - video_scr_t0 ) ) / dpy_den ; // ms/f
                                    final int maxVideoDelay = Math.min(avg_dpy_duration, MAXIMUM_VIDEO_ASYNC);
                                    video_dpts_count++;
                                    // video_dpts_cum = d_avpts + VIDEO_DPTS_COEFF * video_dpts_cum;
                                    video_dpts_cum = d_vpts + VIDEO_DPTS_COEFF * video_dpts_cum;
                                    final int video_dpts_avg_diff = video_dpts_count >= VIDEO_DPTS_NUM ? getVideoDPTSAvg() : 0;
                                    final int dt = (int) ( video_dpts_avg_diff / playSpeed + 0.5f );
                                    // final int dt = (int) ( d_vpts  / playSpeed + 0.5f );
                                    // final int dt = (int) ( d_avpts / playSpeed + 0.5f );
                                    final TextureFrame _nextFrame = nextFrame;
                                    if( dt > maxVideoDelay ) {
                                        cachedFrame = nextFrame;
                                        nextFrame = null;
                                    } else if ( !droppedFrame && dt < -maxVideoDelay && videoFramesDecoded.size() > 0 ) {
                                        // only drop if prev. frame has not been dropped and 
                                        // frame is too late and one decoded frame is already available.
                                        dropFrame = true;
                                    }
                                    video_pts_last = video_pts;
                                    if( DEBUG ) {
                                        System.err.println( "AV_: dT "+(currentTimeMillis-lastTimeMillis)+", "+
                                                getPerfStringImpl( video_scr, video_pts, d_vpts,
                                                                   audio_scr, audio_pts, d_apts,
                                                                   video_dpts_avg_diff ) + 
                                                                   ", avg dpy-fps "+avg_dpy_duration+" ms/f, maxD "+maxVideoDelay+" ms, "+_nextFrame+", playCached " + playCached + ", dropFrame "+dropFrame);
                                    }
                                }
                            } else if( DEBUG ) {
                                System.err.println("Invalid PTS: "+nextFrame);
                            }
                            if( null != nextFrame ) {
                                final TextureFrame _lastFrame = lastFrame;
                                lastFrame = nextFrame;
                                videoFramesFree.putBlocking(_lastFrame);
                            }
                        } else if( DEBUG ) {                            
                            final int video_pts = lastFrame.getPTS();
                            final int audio_pts = getAudioPTSImpl();
                            final int audio_scr = (int) ( ( currentTimeMillis - audio_scr_t0 ) * playSpeed );
                            final int d_apts;
                            if( audio_pts != TimeFrameI.INVALID_PTS ) {
                                d_apts = audio_pts - audio_scr;
                            } else {
                                d_apts = 0;
                            }
                            final int video_scr = video_scr_pts + (int) ( ( currentTimeMillis - video_scr_t0 ) * playSpeed );
                            final int d_vpts = video_pts - video_scr;
                            System.err.println( "AV~: dT "+(currentTimeMillis-lastTimeMillis)+", "+
                                    getPerfStringImpl( video_scr, video_pts, d_vpts, audio_scr, audio_pts, d_apts, 0 ) + ", droppedFrame "+droppedFrame);
                        }
                        lastTimeMillis = currentTimeMillis;
                    } while( dropFrame );
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            displayedFrameCount++;
            return lastFrame;
        }
    }
    protected void preNextTextureImpl(GL gl) {}
    protected void postNextTextureImpl(GL gl) {}
    /**
     * Process stream until the next video frame, i.e. {@link TextureFrame}, has been reached.
     * Audio frames, i.e. {@link AudioSink.AudioFrame}, shall be handled in the process.
     * <p>
     * Video frames shall be ignored, if {@link #getVID()} is {@link #STREAM_ID_NONE}.
     * </p> 
     * <p>
     * Audio frames shall be ignored, if {@link #getAID()} is {@link #STREAM_ID_NONE}.
     * </p>
     * <p>
     * Method may be invoked on the <a href="#streamworker"><i>StreamWorker</i> decoding thread</a>.
     * </p> 
     * <p>
     * Implementation shall care of OpenGL synchronization as required, e.g. glFinish()/glFlush()!
     * </p>
     * @param gl valid and current GL instance, shall be <code>null</code> for audio only. 
     * @param nextFrame the {@link TextureFrame} to store the video PTS and texture data,
     *                  shall be <code>null</code> for audio only.
     * @return the last processed video PTS value, maybe {@link TimeFrameI#INVALID_PTS} if video frame is invalid or n/a.
     *         Will be {@link TimeFrameI#END_OF_STREAM_PTS} if end of stream reached. 
     */
    protected abstract int getNextTextureImpl(GL gl, TextureFrame nextFrame);
    
    /** 
     * {@inheritDoc}
     * <p>
     * Note: All {@link AudioSink} operations are performed from {@link GLMediaPlayerImpl},
     * i.e. {@link #play()}, {@link #pause()}, {@link #seek(int)}, {@link #setPlaySpeed(float)}, {@link #getAudioPTS()}.
     * </p>
     * <p>
     * Implementations using an {@link AudioSink} shall write it's instance to {@link #audioSink}
     * from within their {@link #initStreamImpl(int, int)} implementation.
     * </p>
     */
    @Override
    public final AudioSink getAudioSink() { return audioSink; }
    
    /** 
     * To be called from implementation at 1st PTS after start
     * w/ current pts value in milliseconds.
     * @param audio_scr_t0
     */
    protected void setFirstAudioPTS2SCR(int pts) {
        if( audioSCR_reset ) {
            audio_scr_t0 = Platform.currentTimeMillis() - pts;
            audioSCR_reset = false;
        }
    }
    private void flushAllVideoFrames() {
        if( null != videoFramesFree ) {
            videoFramesFree.resetFull(videoFramesOrig);
            lastFrame = videoFramesFree.get();
            if( null == lastFrame ) { throw new InternalError("XXX"); }
        }
        if( null != videoFramesDecoded ) {
            videoFramesDecoded.clear();
        }
        cachedFrame = null;
    }
    private void resetAllAudioVideoSync() {
        video_dpts_cum = 0;
        video_dpts_count = 0;
        resetAudioVideoPTS();
        flushAllVideoFrames();
        if( null != audioSink ) {
            audioSink.flush();
        }
    }
    private void resetAudioVideoPTS() {
        presentedFrameCount = 0;
        displayedFrameCount = 0;
        decodedFrameCount = 0;
        audioSCR_reset = true;
        videoSCR_reset = true;
    }
    private final int getVideoDPTSAvg() {
        return (int) ( video_dpts_cum * (1.0f - VIDEO_DPTS_COEFF) + 0.5f );
    }
    
    private final void newFrameAvailable(TextureFrame frame, long currentTimeMillis) {
        decodedFrameCount++;
        if( 0 == frame.getDuration() ) { // patch frame duration if not set already 
            frame.setDuration( (int) frame_duration );
        }
        synchronized(eventListenersLock) {
            for(Iterator<GLMediaEventListener> i = eventListeners.iterator(); i.hasNext(); ) {
                i.next().newFrameAvailable(this, frame, currentTimeMillis);
            }
        }
    }
    
    class StreamWorker extends Thread {
        private volatile boolean isRunning = false;
        private volatile boolean isActive = false;
        private volatile boolean isBlocked = false;
        
        private volatile boolean shallPause = true;
        private volatile boolean shallStop = false;
        
        private volatile StreamException streamErr = null;
        private volatile GLContext sharedGLCtx = null;
        private boolean sharedGLCtxCurrent = false;
        private GLDrawable dummyDrawable = null;
        
        /** 
         * Starts this daemon thread, 
         * which initializes the stream first via {@link GLMediaPlayerImpl#initStreamImpl(int, int)} first.
         * <p>
         * After stream initialization, this thread pauses!
         * </p>
         **/ 
        StreamWorker() {
            setDaemon(true);
            start();
        }
        
        private void makeCurrent(GLContext ctx) {
            if( GLContext.CONTEXT_NOT_CURRENT >= ctx.makeCurrent() ) {
                throw new GLException("Couldn't make ctx current: "+ctx);
            }
        }
        
        private void destroySharedGL() {
            if( null != sharedGLCtx ) {
                if( sharedGLCtx.isCreated() ) {
                    // Catch dispose GLExceptions by GLEventListener, just 'print' them
                    // so we can continue with the destruction.
                    try {
                        sharedGLCtx.destroy();
                    } catch (GLException gle) {
                        gle.printStackTrace();
                    }
                }
                sharedGLCtx = null;            
            }
            if( null != dummyDrawable ) {
                final AbstractGraphicsDevice device = dummyDrawable.getNativeSurface().getGraphicsConfiguration().getScreen().getDevice();
                dummyDrawable.setRealized(false);
                dummyDrawable = null;
                device.close();
            }            
        }
        
        public synchronized void initGL(GL gl) {
            final GLContext glCtx = gl.getContext();
            final boolean glCtxCurrent = glCtx.isCurrent();
            final GLProfile glp = gl.getGLProfile();
            final GLDrawableFactory factory = GLDrawableFactory.getFactory(glp);
            final AbstractGraphicsDevice device = glCtx.getGLDrawable().getNativeSurface().getGraphicsConfiguration().getScreen().getDevice();
            dummyDrawable = factory.createDummyDrawable(device, true, glp); // own device!
            dummyDrawable.setRealized(true);
            sharedGLCtx = dummyDrawable.createContext(glCtx);
            makeCurrent(sharedGLCtx);
            if( glCtxCurrent ) {
                makeCurrent(glCtx);
            } else {
                sharedGLCtx.release();
            }
        }
        public synchronized void doPause() {
            if( isActive ) {
                shallPause = true;
                if( Thread.currentThread() != this ) {
                    if( isBlocked && isActive ) {
                        this.interrupt();
                    }
                    while( isActive ) {
                        try {
                            this.wait(); // wait until paused
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        public synchronized void doResume() {
            if( isRunning && !isActive ) {
                shallPause = false;
                if( Thread.currentThread() != this ) {
                    while( !isActive ) {
                        this.notify();  // wake-up pause-block
                        try {
                            this.wait(); // wait until resumed 
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        public synchronized void doStop() {
            if( isRunning ) {
                shallStop = true;
                if( Thread.currentThread() != this ) {
                    if( isBlocked && isRunning ) {
                        this.interrupt();
                    }
                    while( isRunning ) {
                        this.notify();  // wake-up pause-block (opt)
                        try {
                            this.wait();  // wait until stopped
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        public boolean isRunning() { return isRunning; }
        public boolean isActive() { return isActive; }
        public StreamException getStreamErr() { return streamErr; } 
        
        public void run() {
            setName(getName()+"-StreamWorker_"+StreamWorkerInstanceId);
            StreamWorkerInstanceId++;
            
            synchronized ( this ) {
                isRunning = true;
                try {
                    isBlocked = true;
                    initStreamImpl(vid, aid);
                    isBlocked = false;
                } catch (Throwable t) {
                    streamErr = new StreamException(t.getClass().getSimpleName()+" while initializing: "+GLMediaPlayerImpl.this.toString(), t);
                    isBlocked = false;
                    isRunning = false;
                    changeState(GLMediaEventListener.EVENT_CHANGE_ERR, GLMediaPlayer.State.Uninitialized);
                    return; // end of thread!
                } // also initializes width, height, .. etc
            }
            
            while( !shallStop ){
                if( shallPause ) {
                    synchronized ( this ) {
                        if( sharedGLCtxCurrent ) {
                            postNextTextureImpl(sharedGLCtx.getGL());
                            sharedGLCtx.release();
                        }
                        while( shallPause && !shallStop ) {
                            isActive = false;
                            this.notify();   // wake-up doPause()
                            try {
                                this.wait(); // wait until resumed
                            } catch (InterruptedException e) {
                                if( !shallPause ) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        if( sharedGLCtxCurrent ) {
                            makeCurrent(sharedGLCtx);
                            preNextTextureImpl(sharedGLCtx.getGL());
                        }
                        isActive = true;
                        this.notify(); // wake-up doResume()
                    }
                }
                if( !sharedGLCtxCurrent && null != sharedGLCtx ) {
                    synchronized ( this ) {
                        if( null != sharedGLCtx ) {
                            makeCurrent( sharedGLCtx );
                            preNextTextureImpl(sharedGLCtx.getGL());
                            sharedGLCtxCurrent = true;
                        }
                        if( null == videoFramesFree ) {
                            throw new InternalError("XXX videoFramesFree is null");
                        }
                    }
                }
                
                if( !shallStop ) {
                    TextureFrame nextFrame = null;
                    try {
                        final GL gl;
                        isBlocked = true;
                        if( null != videoFramesFree ) {
                            nextFrame = videoFramesFree.getBlocking();
                            nextFrame.setPTS( TimeFrameI.INVALID_PTS ); // mark invalid until processed!
                            gl = sharedGLCtx.getGL();
                        } else {
                            gl = null;
                        }
                        isBlocked = false;
                        final int vPTS = getNextTextureImpl(gl, nextFrame);
                        if( TimeFrameI.INVALID_PTS != vPTS ) {
                            if( null != nextFrame ) {
                                if( STREAM_WORKER_DELAY > 0 ) {
                                    Thread.sleep(STREAM_WORKER_DELAY);
                                }
                                if( !videoFramesDecoded.put(nextFrame) ) {
                                    throw new InternalError("XXX: free "+videoFramesFree+", decoded "+videoFramesDecoded+", "+GLMediaPlayerImpl.this); 
                                }
                                newFrameAvailable(nextFrame, Platform.currentTimeMillis());
                                nextFrame = null;
                            } else {
                                // audio only
                                if( TimeFrameI.END_OF_STREAM_PTS == vPTS ) {
                                    // state transition incl. notification
                                    shallPause = true;
                                    isActive = false;
                                    pauseImpl(GLMediaEventListener.EVENT_CHANGE_EOS);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        isBlocked = false;
                        if( !shallStop && !shallPause ) {
                            streamErr = new StreamException("InterruptedException while decoding: "+GLMediaPlayerImpl.this.toString(), e);
                        }
                    } catch (Throwable t) {
                        streamErr = new StreamException(t.getClass().getSimpleName()+" while decoding: "+GLMediaPlayerImpl.this.toString(), t);
                    } finally {
                        if( null != nextFrame ) { // put back
                            videoFramesFree.put(nextFrame);
                        }
                        if( null != streamErr ) {
                            if( DEBUG ) {
                                final Throwable t = null != streamErr.getCause() ? streamErr.getCause() : streamErr;
                                System.err.println("Caught StreamException: "+t.getMessage());
                                t.printStackTrace();
                            }
                            // state transition incl. notification
                            shallPause = true;
                            isActive = false;
                            pauseImpl(GLMediaEventListener.EVENT_CHANGE_ERR);
                        }
                    }
                }
            }
            synchronized ( this ) {
                if( sharedGLCtxCurrent ) {
                    postNextTextureImpl(sharedGLCtx.getGL());
                }
                destroySharedGL();
                isRunning = false;
                isActive = false;
                this.notify(); // wake-up doStop()
            }
        }
    }    
    static int StreamWorkerInstanceId = 0;    
    private StreamWorker streamWorker = null;
    
    protected final int addStateEventMask(int event_mask, State newState) {
        if( state != newState ) {
            switch( newState ) {
                case Uninitialized:
                    event_mask |= GLMediaEventListener.EVENT_CHANGE_UNINIT;
                    break;
                case Initialized:
                    event_mask |= GLMediaEventListener.EVENT_CHANGE_INIT;
                    break;
                case Playing:
                    event_mask |= GLMediaEventListener.EVENT_CHANGE_PLAY;
                    break;
                case Paused:
                    event_mask |= GLMediaEventListener.EVENT_CHANGE_PAUSE;
                    break;
            }
        }
        return event_mask;
    }
    
    protected final void attributesUpdated(int event_mask) {
        if( 0 != event_mask ) {
            final long now = Platform.currentTimeMillis();
            synchronized(eventListenersLock) {
                for(Iterator<GLMediaEventListener> i = eventListeners.iterator(); i.hasNext(); ) {
                    i.next().attributesChanged(this, event_mask, now);
                }
            }
        }
    }
    
    protected final void changeState(int event_mask, State newState) {
        event_mask = addStateEventMask(event_mask, newState);
        if( 0 != event_mask ) {
            state = newState;
            attributesUpdated( event_mask );
        }
    }
    
    protected final void updateAttributes(int vid, int aid, int width, int height, int bps_stream, 
                                          int bps_video, int bps_audio, float fps, 
                                          int videoFrames, int audioFrames, int duration, String vcodec, String acodec) {
        int event_mask = 0;
        if( state == State.Uninitialized ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_INIT;
            state = State.Initialized;
        }
        if( STREAM_ID_AUTO == vid ) {
            vid = STREAM_ID_NONE;
        }
        if( this.vid != vid ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_VID;
            this.vid = vid;
        }   
        if( STREAM_ID_AUTO == vid ) {
            vid = STREAM_ID_NONE;
        }
        if( this.aid != aid ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_AID;
            this.aid = aid;
        }   
        if( this.width != width || this.height != height ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_SIZE;
            this.width = width;
            this.height = height;
        }   
        if( this.fps != fps ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_FPS;
            this.fps = fps;
            this.frame_duration = 1000f / (float)fps;
        }
        if( this.bps_stream != bps_stream || this.bps_video != bps_video || this.bps_audio != bps_audio ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_BPS;
            this.bps_stream = bps_stream;
            this.bps_video = bps_video;
            this.bps_audio = bps_audio;
        }
        if( this.videoFrames != videoFrames || this.audioFrames != audioFrames || this.duration != duration ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_LENGTH;
            this.videoFrames = videoFrames;
            this.audioFrames = audioFrames;
            this.duration = duration;
        }
        if( (null!=acodec && acodec.length()>0 && !this.acodec.equals(acodec)) ) { 
            event_mask |= GLMediaEventListener.EVENT_CHANGE_CODEC;
            this.acodec = acodec;
        }
        if( (null!=vcodec && vcodec.length()>0 && !this.vcodec.equals(vcodec)) ) {
            event_mask |= GLMediaEventListener.EVENT_CHANGE_CODEC;
            this.vcodec = vcodec;
        }
        if(0==event_mask) {
            return;
        }
        attributesUpdated(event_mask);
    }

    @Override
    public final State destroy(GL gl) {
        synchronized( stateLock ) {
            streamWorker.doStop();
            streamWorker = null;
            destroyImpl(gl);
            removeAllTextureFrames(gl);
            textureCount=0;
            changeState(0, State.Uninitialized);
            return state;
        }
    }
    protected abstract void destroyImpl(GL gl);

    @Override
    public final URI getURI() {
        return streamLoc;
    }

    @Override
    public final int getVID() { return vid; }
    
    @Override
    public final int getAID() { return aid; }
    
    @Override
    public final String getVideoCodec() {
        return vcodec;
    }

    @Override
    public final String getAudioCodec() {
        return acodec;
    }

    @Override
    public final int getVideoFrames() {
        return videoFrames;
    }
    
    @Override
    public final int getAudioFrames() {
        return audioFrames;
    }

    @Override
    public final int getDuration() {
        return duration;
    }
    
    @Override
    public final long getStreamBitrate() {
        return bps_stream;
    }

    @Override
    public final int getVideoBitrate() {
        return bps_video;
    }
    
    @Override
    public final int getAudioBitrate() {
        return bps_audio;
    }
    
    @Override
    public final float getFramerate() {
        return fps;
    }

    @Override
    public final int getWidth() {
        return width;
    }

    @Override
    public final int getHeight() {
        return height;
    }

    @Override
    public final String toString() {
        final float tt = getDuration() / 1000.0f;
        final String loc = ( null != streamLoc ) ? streamLoc.toString() : "<undefined stream>" ;
        final int freeVideoFrames = null != videoFramesFree ? videoFramesFree.size() : 0;
        final int decVideoFrames = null != videoFramesDecoded ? videoFramesDecoded.size() : 0;
        final int video_scr = video_scr_pts + (int) ( ( Platform.currentTimeMillis() - video_scr_t0 ) * playSpeed );        
        return "GLMediaPlayer["+state+", vSCR "+video_scr+", frames[p "+presentedFrameCount+", d "+decodedFrameCount+", t "+videoFrames+" ("+tt+" s)], "+
               "speed "+playSpeed+", "+bps_stream+" bps, "+
               "Texture[count "+textureCount+", free "+freeVideoFrames+", dec "+decVideoFrames+", target "+toHexString(textureTarget)+", format "+toHexString(textureFormat)+", type "+toHexString(textureType)+"], "+               
               "Video[id "+vid+", <"+vcodec+">, "+width+"x"+height+", "+fps+" fps, "+frame_duration+" fdur, "+bps_video+" bps], "+
               "Audio[id "+aid+", <"+acodec+">, "+bps_audio+" bps, "+audioFrames+" frames], uri "+loc+"]";
    }
    
    @Override
    public final String getPerfString() {
        final long currentTimeMillis = Platform.currentTimeMillis();
        final int video_scr = video_scr_pts + (int) ( ( currentTimeMillis - video_scr_t0 ) * playSpeed );        
        final int d_vpts = video_pts_last - video_scr;
        final int audio_scr = (int) ( ( currentTimeMillis - audio_scr_t0 ) * playSpeed );
        final int audio_pts = getAudioPTSImpl();
        final int d_apts = audio_pts - audio_scr;
        return getPerfStringImpl( video_scr, video_pts_last, d_vpts, audio_scr, audio_pts, d_apts, getVideoDPTSAvg() );
    }
    private final String getPerfStringImpl(final int video_scr, final int video_pts, final int d_vpts,
                                           final int audio_scr, final int audio_pts, final int d_apts,
                                           final int video_dpts_avg_diff) {
        final float tt = getDuration() / 1000.0f;        
        final String audioSinkInfo;
        final AudioSink audioSink = getAudioSink();
        if( null != audioSink ) {
            audioSinkInfo = "AudioSink[frames [p "+audioSink.getEnqueuedFrameCount()+", q "+audioSink.getQueuedFrameCount()+", f "+audioSink.getFreeFrameCount()+", c "+audioSink.getFrameCount()+"], time "+audioSink.getQueuedTime()+", bytes "+audioSink.getQueuedByteCount()+"]";
        } else {
            audioSinkInfo = "";
        }
        final int freeVideoFrames = null != videoFramesFree ? videoFramesFree.size() : 0;
        final int decVideoFrames = null != videoFramesDecoded ? videoFramesDecoded.size() : 0;
        return state+", frames[(p "+presentedFrameCount+", d "+decodedFrameCount+") / "+videoFrames+", "+tt+" s], "+
               "speed " + playSpeed+", dAV "+( d_vpts - d_apts )+", vSCR "+video_scr+", vpts "+video_pts+", dSCR["+d_vpts+", avrg "+video_dpts_avg_diff+"], "+
               "aSCR "+audio_scr+", apts "+audio_pts+" ( "+d_apts+" ), "+audioSinkInfo+
               ", Texture[count "+textureCount+", free "+freeVideoFrames+", dec "+decVideoFrames+"]";
    }

    @Override
    public final void addEventListener(GLMediaEventListener l) {
        if(l == null) {
            return;
        }
        synchronized(eventListenersLock) {
            eventListeners.add(l);
        }
    }

    @Override
    public final void removeEventListener(GLMediaEventListener l) {
        if (l == null) {
            return;
        }
        synchronized(eventListenersLock) {
            eventListeners.remove(l);
        }
    }

    @Override
    public final GLMediaEventListener[] getEventListeners() {
        synchronized(eventListenersLock) {
            return eventListeners.toArray(new GLMediaEventListener[eventListeners.size()]);
        }
    }

    private Object eventListenersLock = new Object();

    protected static final String toHexString(long v) {
        return "0x"+Long.toHexString(v);
    }
    protected static final String toHexString(int v) {
        return "0x"+Integer.toHexString(v);
    }
}