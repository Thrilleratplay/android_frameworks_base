/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.view;

import android.content.ComponentCallbacks2;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLUtils;
import android.opengl.ManagedEGLContext;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
import com.google.android.gles_jni.EGLImpl;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import java.io.File;
import java.io.PrintWriter;
import java.util.concurrent.locks.ReentrantLock;

import static javax.microedition.khronos.egl.EGL10.*;

/**
 * Interface for rendering a ViewAncestor using hardware acceleration.
 * 
 * @hide
 */
public abstract class HardwareRenderer {
    static final String LOG_TAG = "HardwareRenderer";

    /**
     * Name of the file that holds the shaders cache.
     */
    private static final String CACHE_PATH_SHADERS = "com.android.opengl.shaders_cache";

    /**
     * Turn on to only refresh the parts of the screen that need updating.
     * When turned on the property defined by {@link #RENDER_DIRTY_REGIONS_PROPERTY}
     * must also have the value "true". 
     */
    public static final boolean RENDER_DIRTY_REGIONS = true;

    /**
     * System property used to enable or disable dirty regions invalidation.
     * This property is only queried if {@link #RENDER_DIRTY_REGIONS} is true.
     * The default value of this property is assumed to be true.
     * 
     * Possible values:
     * "true", to enable partial invalidates
     * "false", to disable partial invalidates
     */
    static final String RENDER_DIRTY_REGIONS_PROPERTY = "debug.hwui.render_dirty_regions";

    /**
     * System property used to enable or disable hardware rendering profiling.
     * The default value of this property is assumed to be false.
     *
     * When profiling is enabled, the adb shell dumpsys gfxinfo command will
     * output extra information about the time taken to execute by the last
     * frames.
     *
     * Possible values:
     * "true", to enable profiling
     * "false", to disable profiling
     *
     * @hide
     */
    public static final String PROFILE_PROPERTY = "debug.hwui.profile";

    /**
     * System property used to enable or disable hardware rendering profiling
     * visualization. The default value of this property is assumed to be false.
     *
     * This property is only taken into account when {@link #PROFILE_PROPERTY} is
     * turned on.
     *
     * Possible values:
     * "true", to enable on screen profiling
     * "false", to disable on screen profiling
     *
     * @hide
     */
    public static final String PROFILE_VISUALIZER_PROPERTY = "debug.hwui.profile_visualizer";

    /**
     * System property used to specify the number of frames to be used
     * when doing hardware rendering profiling.
     * The default value of this property is #PROFILE_MAX_FRAMES.
     *
     * When profiling is enabled, the adb shell dumpsys gfxinfo command will
     * output extra information about the time taken to execute by the last
     * frames.
     *
     * Possible values:
     * "60", to set the limit of frames to 60
     */
    static final String PROFILE_MAXFRAMES_PROPERTY = "debug.hwui.profile.maxframes";

    /**
     * System property used to debug EGL configuration choice.
     * 
     * Possible values:
     * "choice", print the chosen configuration only
     * "all", print all possible configurations
     */
    static final String PRINT_CONFIG_PROPERTY = "debug.hwui.print_config";

    /**
     * Turn on to draw dirty regions every other frame.
     *
     * Possible values:
     * "true", to enable dirty regions debugging
     * "false", to disable dirty regions debugging
     * 
     * @hide
     */
    public static final String DEBUG_DIRTY_REGIONS_PROPERTY = "debug.hwui.show_dirty_regions";

    /**
     * Turn on to flash hardware layers when they update.
     *
     * Possible values:
     * "true", to enable hardware layers updates debugging
     * "false", to disable hardware layers updates debugging
     *
     * @hide
     */
    public static final String DEBUG_SHOW_LAYERS_UPDATES_PROPERTY =
            "debug.hwui.show_layers_updates";

    /**
     * Turn on to show overdraw level.
     *
     * Possible values:
     * "true", to enable overdraw debugging
     * "false", to disable overdraw debugging
     *
     * @hide
     */
    public static final String DEBUG_SHOW_OVERDRAW_PROPERTY = "debug.hwui.show_overdraw";

    /**
     * A process can set this flag to false to prevent the use of hardware
     * rendering.
     * 
     * @hide
     */
    public static boolean sRendererDisabled = false;

    /**
     * Further hardware renderer disabling for the system process.
     * 
     * @hide
     */
    public static boolean sSystemRendererDisabled = false;

    /**
     * Number of frames to profile.
     */
    private static final int PROFILE_MAX_FRAMES = 128;

    /**
     * Number of floats per profiled frame.
     */
    private static final int PROFILE_FRAME_DATA_COUNT = 3;

    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * Invoke this method to disable hardware rendering in the current process.
     * 
     * @hide
     */
    public static void disable(boolean system) {
        sRendererDisabled = true;
        if (system) {
            sSystemRendererDisabled = true;
        }
    }

    /**
     * Indicates whether hardware acceleration is available under any form for
     * the view hierarchy.
     * 
     * @return True if the view hierarchy can potentially be hardware accelerated,
     *         false otherwise
     */
    public static boolean isAvailable() {
        return GLES20Canvas.isAvailable();
    }

    /**
     * Destroys the hardware rendering context.
     * 
     * @param full If true, destroys all associated resources.
     */
    abstract void destroy(boolean full);

    /**
     * Initializes the hardware renderer for the specified surface.
     * 
     * @param surface The surface to hardware accelerate
     * 
     * @return True if the initialization was successful, false otherwise.
     */
    abstract boolean initialize(Surface surface) throws Surface.OutOfResourcesException;
    
    /**
     * Updates the hardware renderer for the specified surface.
     *
     * @param surface The surface to hardware accelerate
     */
    abstract void updateSurface(Surface surface) throws Surface.OutOfResourcesException;

    /**
     * Destroys the layers used by the specified view hierarchy.
     * 
     * @param view The root of the view hierarchy
     */
    abstract void destroyLayers(View view);

    /**
     * Destroys all hardware rendering resources associated with the specified
     * view hierarchy.
     * 
     * @param view The root of the view hierarchy
     */
    abstract void destroyHardwareResources(View view);
    
    /**
     * This method should be invoked whenever the current hardware renderer
     * context should be reset.
     *
     * @param surface The surface to hardware accelerate
     */
    abstract void invalidate(Surface surface);

    /**
     * This method should be invoked to ensure the hardware renderer is in
     * valid state (for instance, to ensure the correct EGL context is bound
     * to the current thread.)
     * 
     * @return true if the renderer is now valid, false otherwise
     */
    abstract boolean validate();

    /**
     * This method ensures the hardware renderer is in a valid state
     * before executing the specified action.
     * 
     * This method will attempt to set a valid state even if the window
     * the renderer is attached to was destroyed.
     *
     * @return true if the action was run
     */
    abstract boolean safelyRun(Runnable action);

    /**
     * Setup the hardware renderer for drawing. This is called whenever the
     * size of the target surface changes or when the surface is first created.
     * 
     * @param width Width of the drawing surface.
     * @param height Height of the drawing surface.
     */
    abstract void setup(int width, int height);

    /**
     * Gets the current width of the surface. This is the width that the surface
     * was last set to in a call to {@link #setup(int, int)}.
     *
     * @return the current width of the surface
     */
    abstract int getWidth();

    /**
     * Gets the current height of the surface. This is the height that the surface
     * was last set to in a call to {@link #setup(int, int)}.
     *
     * @return the current width of the surface
     */
    abstract int getHeight();

    /**
     * Gets the current canvas associated with this HardwareRenderer.
     *
     * @return the current HardwareCanvas
     */
    abstract HardwareCanvas getCanvas();

    /**
     * Outputs extra debugging information in the specified file descriptor.
     * @param pw
     */
    abstract void dumpGfxInfo(PrintWriter pw);

    /**
     * Outputs the total number of frames rendered (used for fps calculations)
     *
     * @return the number of frames rendered
     */
    abstract long getFrameCount();

    /**
     * Loads system properties used by the renderer. This method is invoked
     * whenever system properties are modified. Implementations can use this
     * to trigger live updates of the renderer based on properties.
     *
     * @param surface The surface to update with the new properties.
     *                Can be null.
     *
     * @return True if a property has changed.
     */
    abstract boolean loadSystemProperties(Surface surface);

    private static native boolean nLoadProperties();

    /**
     * Sets the directory to use as a persistent storage for hardware rendering
     * resources.
     * 
     * @param cacheDir A directory the current process can write to
     */
    public static void setupDiskCache(File cacheDir) {
        nSetupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath());
    }

    private static native void nSetupShadersDiskCache(String cacheFile);

    /**
     * Notifies EGL that the frame is about to be rendered.
     * @param size
     */
    static void beginFrame(int[] size) {
        nBeginFrame(size);
    }

    private static native void nBeginFrame(int[] size);

    /**
     * Preserves the back buffer of the current surface after a buffer swap.
     * Calling this method sets the EGL_SWAP_BEHAVIOR attribute of the current
     * surface to EGL_BUFFER_PRESERVED. Calling this method requires an EGL
     * config that supports EGL_SWAP_BEHAVIOR_PRESERVED_BIT.
     *
     * @return True if the swap behavior was successfully changed,
     *         false otherwise.
     */
    static boolean preserveBackBuffer() {
        return nPreserveBackBuffer();
    }

    private static native boolean nPreserveBackBuffer();

    /**
     * Indicates whether the current surface preserves its back buffer
     * after a buffer swap.
     *
     * @return True, if the surface's EGL_SWAP_BEHAVIOR is EGL_BUFFER_PRESERVED,
     *         false otherwise
     */
    static boolean isBackBufferPreserved() {
        return nIsBackBufferPreserved();
    }

    private static native boolean nIsBackBufferPreserved();

    /**
     * Indicates that the specified hardware layer needs to be updated
     * as soon as possible.
     * 
     * @param layer The hardware layer that needs an update
     */
    abstract void pushLayerUpdate(HardwareLayer layer);

    /**
     * Interface used to receive callbacks whenever a view is drawn by
     * a hardware renderer instance.
     */
    interface HardwareDrawCallbacks {
        /**
         * Invoked before a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePreDraw(HardwareCanvas canvas);

        /**
         * Invoked after a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePostDraw(HardwareCanvas canvas);
    }

    /**
     * Draws the specified view.
     *
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     * @param callbacks Callbacks invoked when drawing happens.
     * @param dirty The dirty rectangle to update, can be null.
     * 
     * @return true if the dirty rect was ignored, false otherwise
     */
    abstract boolean draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
            Rect dirty);

    /**
     * Creates a new display list that can be used to record batches of
     * drawing operations.
     * 
     * @param name The name of the display list, used for debugging purpose.
     *             May be null
     * 
     * @return A new display list.
     */
    public abstract DisplayList createDisplayList(String name);

    /**
     * Creates a new hardware layer. A hardware layer built by calling this
     * method will be treated as a texture layer, instead of as a render target.
     * 
     * @param isOpaque Whether the layer should be opaque or not
     * 
     * @return A hardware layer
     */
    abstract HardwareLayer createHardwareLayer(boolean isOpaque);

    /**
     * Creates a new hardware layer.
     * 
     * @param width The minimum width of the layer
     * @param height The minimum height of the layer
     * @param isOpaque Whether the layer should be opaque or not
     * 
     * @return A hardware layer
     */
    abstract HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque);

    /**
     * Creates a new {@link SurfaceTexture} that can be used to render into the
     * specified hardware layer.
     * 
     *
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     * 
     * @return A {@link SurfaceTexture}
     */
    abstract SurfaceTexture createSurfaceTexture(HardwareLayer layer);

    /**
     * Sets the {@link android.graphics.SurfaceTexture} that will be used to
     * render into the specified hardware layer.
     *
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     * @param surfaceTexture The {@link android.graphics.SurfaceTexture} to use for the layer
     */
    abstract void setSurfaceTexture(HardwareLayer layer, SurfaceTexture surfaceTexture);

    /**
     * Detaches the specified functor from the current functor execution queue.
     * 
     * @param functor The native functor to remove from the execution queue.
     *                
     * @see HardwareCanvas#callDrawGLFunction(int) 
     * @see #attachFunctor(android.view.View.AttachInfo, int) 
     */
    abstract void detachFunctor(int functor);

    /**
     * Schedules the specified functor in the functors execution queue.
     *
     * @param attachInfo AttachInfo tied to this renderer.
     * @param functor The native functor to insert in the execution queue.
     *
     * @see HardwareCanvas#callDrawGLFunction(int)
     * @see #detachFunctor(int)
     *
     * @return true if the functor was attached successfully
     */
    abstract boolean attachFunctor(View.AttachInfo attachInfo, int functor);

    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewAncestor has
     * potentially lost the hardware renderer. The hardware renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     *
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param surface The surface to hardware accelerate
     *                
     * @return true if the surface was initialized, false otherwise. Returning
     *         false might mean that the surface was already initialized.
     */
    boolean initializeIfNeeded(int width, int height, Surface surface)
            throws Surface.OutOfResourcesException {
        if (isRequested()) {
            // We lost the gl context, so recreate it.
            if (!isEnabled()) {
                if (initialize(surface)) {
                    setup(width, height);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates a hardware renderer using OpenGL.
     * 
     * @param glVersion The version of OpenGL to use (1 for OpenGL 1, 11 for OpenGL 1.1, etc.)
     * @param translucent True if the surface is translucent, false otherwise
     * 
     * @return A hardware renderer backed by OpenGL.
     */
    static HardwareRenderer createGlRenderer(int glVersion, boolean translucent) {
        switch (glVersion) {
            case 2:
                return Gl20Renderer.create(translucent);
        }
        throw new IllegalArgumentException("Unknown GL version: " + glVersion);
    }

    /**
     * Invoke this method when the system is running out of memory. This
     * method will attempt to recover as much memory as possible, based on
     * the specified hint.
     * 
     * @param level Hint about the amount of memory that should be trimmed,
     *              see {@link android.content.ComponentCallbacks}
     */
    static void trimMemory(int level) {
        startTrimMemory(level);
        endTrimMemory();
    }

    /**
     * Starts the process of trimming memory. Usually this call will setup
     * hardware rendering context and reclaim memory.Extra cleanup might
     * be required by calling {@link #endTrimMemory()}.
     * 
     * @param level Hint about the amount of memory that should be trimmed,
     *              see {@link android.content.ComponentCallbacks}
     */
    static void startTrimMemory(int level) {
        Gl20Renderer.startTrimMemory(level);
    }

    /**
     * Finishes the process of trimming memory. This method will usually
     * cleanup special resources used by the memory trimming process.
     */
    static void endTrimMemory() {
        Gl20Renderer.endTrimMemory();
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @return True if hardware acceleration is in use, false otherwise.
     */
    boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @param enabled True if the hardware renderer is in use, false otherwise.
     */
    void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Indicates whether hardware acceleration is currently request but not
     * necessarily enabled yet.
     * 
     * @return True if requested, false otherwise.
     */
    boolean isRequested() {
        return mRequested;
    }

    /**
     * Indicates whether hardware acceleration is currently requested but not
     * necessarily enabled yet.
     * 
     * @return True to request hardware acceleration, false otherwise.
     */
    void setRequested(boolean requested) {
        mRequested = requested;
    }

    @SuppressWarnings({"deprecation"})
    static abstract class GlRenderer extends HardwareRenderer {
        static final int SURFACE_STATE_ERROR = 0;
        static final int SURFACE_STATE_SUCCESS = 1;
        static final int SURFACE_STATE_UPDATED = 2;

        static final int FUNCTOR_PROCESS_DELAY = 4;

        static EGL10 sEgl;
        static EGLDisplay sEglDisplay;
        static EGLConfig sEglConfig;
        static final Object[] sEglLock = new Object[0];
        int mWidth = -1, mHeight = -1;

        static final ThreadLocal<ManagedEGLContext> sEglContextStorage
                = new ThreadLocal<ManagedEGLContext>();

        EGLContext mEglContext;
        Thread mEglThread;

        EGLSurface mEglSurface;
        
        GL mGl;
        HardwareCanvas mCanvas;

        long mFrameCount;
        Paint mDebugPaint;

        static boolean sDirtyRegions;
        static final boolean sDirtyRegionsRequested;
        static {
            String dirtyProperty = SystemProperties.get(RENDER_DIRTY_REGIONS_PROPERTY, "true");
            //noinspection PointlessBooleanExpression,ConstantConditions
            sDirtyRegions = RENDER_DIRTY_REGIONS && "true".equalsIgnoreCase(dirtyProperty);
            sDirtyRegionsRequested = sDirtyRegions;
        }

        boolean mDirtyRegionsEnabled;
        boolean mUpdateDirtyRegions;

        boolean mProfileEnabled;
        boolean mProfileVisualizerEnabled;
        float[] mProfileData;
        ReentrantLock mProfileLock;
        int mProfileCurrentFrame = -PROFILE_FRAME_DATA_COUNT;

        float[][] mProfileRects;
        Paint mProfilePaint;

        boolean mDebugDirtyRegions;
        boolean mShowOverdraw;

        final int mGlVersion;
        final boolean mTranslucent;

        private boolean mDestroyed;

        private final Rect mRedrawClip = new Rect();

        private final int[] mSurfaceSize = new int[2];
        private final FunctorsRunnable mFunctorsRunnable = new FunctorsRunnable();

        GlRenderer(int glVersion, boolean translucent) {
            mGlVersion = glVersion;
            mTranslucent = translucent;

            loadSystemProperties(null);
        }

        @Override
        boolean loadSystemProperties(Surface surface) {
            boolean changed = false;

            boolean value = SystemProperties.getBoolean(PROFILE_PROPERTY, false);
            if (value != mProfileEnabled) {
                changed = true;
                mProfileEnabled = value;

                if (mProfileEnabled) {
                    Log.d(LOG_TAG, "Profiling hardware renderer");
                }

                if (mProfileEnabled) {
                    int maxProfileFrames = SystemProperties.getInt(PROFILE_MAXFRAMES_PROPERTY,
                            PROFILE_MAX_FRAMES);
                    mProfileData = new float[maxProfileFrames * PROFILE_FRAME_DATA_COUNT];
                    for (int i = 0; i < mProfileData.length; i += PROFILE_FRAME_DATA_COUNT) {
                        mProfileData[i] = mProfileData[i + 1] = mProfileData[i + 2] = -1;
                    }

                    mProfileLock = new ReentrantLock();
                } else {
                    mProfileData = null;
                    mProfileLock = null;
                }

                mProfileRects = null;
                mProfilePaint = null;
            }

            value = SystemProperties.getBoolean(PROFILE_VISUALIZER_PROPERTY, false);
            if (value != mProfileVisualizerEnabled) {
                changed = true;
                mProfileVisualizerEnabled = value;

                mProfileRects = null;
                mProfilePaint = null;
            }

            value = SystemProperties.getBoolean(DEBUG_DIRTY_REGIONS_PROPERTY, false);
            if (value != mDebugDirtyRegions) {
                changed = true;
                mDebugDirtyRegions = value;

                if (mDebugDirtyRegions) {
                    Log.d(LOG_TAG, "Debugging dirty regions");
                }
            }

            value = SystemProperties.getBoolean(
                    HardwareRenderer.DEBUG_SHOW_OVERDRAW_PROPERTY, false);
            if (value != mShowOverdraw) {
                changed = true;
                mShowOverdraw = value;

                if (surface != null && isEnabled()) {
                    if (validate()) {
                        sEglConfig = loadEglConfig();
                        invalidate(surface);
                    }
                }
            }

            if (nLoadProperties()) {
                changed = true;
            }

            return changed;
        }

        @Override
        void dumpGfxInfo(PrintWriter pw) {
            if (mProfileEnabled) {
                pw.printf("\n\tDraw\tProcess\tExecute\n");

                mProfileLock.lock();
                try {
                    for (int i = 0; i < mProfileData.length; i += PROFILE_FRAME_DATA_COUNT) {
                        if (mProfileData[i] < 0) {
                            break;
                        }
                        pw.printf("\t%3.2f\t%3.2f\t%3.2f\n", mProfileData[i], mProfileData[i + 1],
                                mProfileData[i + 2]);
                        mProfileData[i] = mProfileData[i + 1] = mProfileData[i + 2] = -1;
                    }
                    mProfileCurrentFrame = mProfileData.length;
                } finally {
                    mProfileLock.unlock();
                }
            }
        }

        @Override
        long getFrameCount() {
            return mFrameCount;
        }

        /**
         * Indicates whether this renderer instance can track and update dirty regions.
         */
        boolean hasDirtyRegions() {
            return mDirtyRegionsEnabled;
        }

        /**
         * Checks for OpenGL errors. If an error has occured, {@link #destroy(boolean)}
         * is invoked and the requested flag is turned off. The error code is
         * also logged as a warning.
         */
        void checkEglErrors() {
            if (isEnabled()) {
                checkEglErrorsForced();
            }
        }

        private void checkEglErrorsForced() {
            int error = sEgl.eglGetError();
            if (error != EGL_SUCCESS) {
                // something bad has happened revert to
                // normal rendering.
                Log.w(LOG_TAG, "EGL error: " + GLUtils.getEGLErrorString(error));
                fallback(error != EGL11.EGL_CONTEXT_LOST);
            }
        }

        private void fallback(boolean fallback) {
            destroy(true);
            if (fallback) {
                // we'll try again if it was context lost
                setRequested(false);
                Log.w(LOG_TAG, "Mountain View, we've had a problem here. " 
                        + "Switching back to software rendering.");
            }
        }

        @Override
        boolean initialize(Surface surface) throws Surface.OutOfResourcesException {
            if (isRequested() && !isEnabled()) {
                initializeEgl();
                mGl = createEglSurface(surface);
                mDestroyed = false;

                if (mGl != null) {
                    int err = sEgl.eglGetError();
                    if (err != EGL_SUCCESS) {
                        destroy(true);
                        setRequested(false);
                    } else {
                        if (mCanvas == null) {
                            mCanvas = createCanvas();
                        }
                        if (mCanvas != null) {
                            setEnabled(true);
                        } else {
                            Log.w(LOG_TAG, "Hardware accelerated Canvas could not be created");
                        }
                    }

                    return mCanvas != null;
                }
            }
            return false;
        }
        
        @Override
        void updateSurface(Surface surface) throws Surface.OutOfResourcesException {
            if (isRequested() && isEnabled()) {
                createEglSurface(surface);
            }
        }

        abstract HardwareCanvas createCanvas();

        abstract int[] getConfig(boolean dirtyRegions);

        void initializeEgl() {
            synchronized (sEglLock) {
                if (sEgl == null && sEglConfig == null) {
                    sEgl = (EGL10) EGLContext.getEGL();
                    
                    // Get to the default display.
                    sEglDisplay = sEgl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
                    
                    if (sEglDisplay == EGL_NO_DISPLAY) {
                        throw new RuntimeException("eglGetDisplay failed "
                                + GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    }
                    
                    // We can now initialize EGL for that display
                    int[] version = new int[2];
                    if (!sEgl.eglInitialize(sEglDisplay, version)) {
                        throw new RuntimeException("eglInitialize failed " +
                                GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    }

                    checkEglErrorsForced();

                    sEglConfig = loadEglConfig();
                }
            }

            ManagedEGLContext managedContext = sEglContextStorage.get();
            mEglContext = managedContext != null ? managedContext.getContext() : null;
            mEglThread = Thread.currentThread();

            if (mEglContext == null) {
                mEglContext = createContext(sEgl, sEglDisplay, sEglConfig);
                sEglContextStorage.set(createManagedContext(mEglContext));
            }
        }

        private EGLConfig loadEglConfig() {
            EGLConfig eglConfig = chooseEglConfig();
            if (eglConfig == null) {
                // We tried to use EGL_SWAP_BEHAVIOR_PRESERVED_BIT, try again without
                if (sDirtyRegions) {
                    sDirtyRegions = false;
                    eglConfig = chooseEglConfig();
                    if (eglConfig == null) {
                        throw new RuntimeException("eglConfig not initialized");
                    }
                } else {
                    throw new RuntimeException("eglConfig not initialized");
                }
            }
            return eglConfig;
        }

        abstract ManagedEGLContext createManagedContext(EGLContext eglContext);

        private EGLConfig chooseEglConfig() {
            EGLConfig[] configs = new EGLConfig[1];
            int[] configsCount = new int[1];
            int[] configSpec = getConfig(sDirtyRegions);

            // Debug
            final String debug = SystemProperties.get(PRINT_CONFIG_PROPERTY, "");
            if ("all".equalsIgnoreCase(debug)) {
                sEgl.eglChooseConfig(sEglDisplay, configSpec, null, 0, configsCount);

                EGLConfig[] debugConfigs = new EGLConfig[configsCount[0]];
                sEgl.eglChooseConfig(sEglDisplay, configSpec, debugConfigs,
                        configsCount[0], configsCount);

                for (EGLConfig config : debugConfigs) {
                    printConfig(config);
                }
            }

            if (!sEgl.eglChooseConfig(sEglDisplay, configSpec, configs, 1, configsCount)) {
                throw new IllegalArgumentException("eglChooseConfig failed " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            } else if (configsCount[0] > 0) {
                if ("choice".equalsIgnoreCase(debug)) {
                    printConfig(configs[0]);
                }
                return configs[0];
            }

            return null;
        }

        private static void printConfig(EGLConfig config) {
            int[] value = new int[1];

            Log.d(LOG_TAG, "EGL configuration " + config + ":");

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_RED_SIZE, value);
            Log.d(LOG_TAG, "  RED_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_GREEN_SIZE, value);
            Log.d(LOG_TAG, "  GREEN_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_BLUE_SIZE, value);
            Log.d(LOG_TAG, "  BLUE_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_ALPHA_SIZE, value);
            Log.d(LOG_TAG, "  ALPHA_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_DEPTH_SIZE, value);
            Log.d(LOG_TAG, "  DEPTH_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_STENCIL_SIZE, value);
            Log.d(LOG_TAG, "  STENCIL_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SAMPLE_BUFFERS, value);
            Log.d(LOG_TAG, "  SAMPLE_BUFFERS = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SAMPLES, value);
            Log.d(LOG_TAG, "  SAMPLES = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SURFACE_TYPE, value);
            Log.d(LOG_TAG, "  SURFACE_TYPE = 0x" + Integer.toHexString(value[0]));

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_CONFIG_CAVEAT, value);
            Log.d(LOG_TAG, "  CONFIG_CAVEAT = 0x" + Integer.toHexString(value[0]));
        }

        GL createEglSurface(Surface surface) throws Surface.OutOfResourcesException {
            // Check preconditions.
            if (sEgl == null) {
                throw new RuntimeException("egl not initialized");
            }
            if (sEglDisplay == null) {
                throw new RuntimeException("eglDisplay not initialized");
            }
            if (sEglConfig == null) {
                throw new RuntimeException("eglConfig not initialized");
            }
            if (Thread.currentThread() != mEglThread) {
                throw new IllegalStateException("HardwareRenderer cannot be used " 
                        + "from multiple threads");
            }

            // In case we need to destroy an existing surface
            destroySurface();

            // Create an EGL surface we can render into.
            if (!createSurface(surface)) {
                return null;
            }

            initCaches();

            return mEglContext.getGL();
        }

        private void enableDirtyRegions() {
            // If mDirtyRegions is set, this means we have an EGL configuration
            // with EGL_SWAP_BEHAVIOR_PRESERVED_BIT set
            if (sDirtyRegions) {
                if (!(mDirtyRegionsEnabled = preserveBackBuffer())) {
                    Log.w(LOG_TAG, "Backbuffer cannot be preserved");
                }
            } else if (sDirtyRegionsRequested) {
                // If mDirtyRegions is not set, our EGL configuration does not
                // have EGL_SWAP_BEHAVIOR_PRESERVED_BIT; however, the default
                // swap behavior might be EGL_BUFFER_PRESERVED, which means we
                // want to set mDirtyRegions. We try to do this only if dirty
                // regions were initially requested as part of the device
                // configuration (see RENDER_DIRTY_REGIONS)
                mDirtyRegionsEnabled = isBackBufferPreserved();
            }
        }

        abstract void initCaches();

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, mGlVersion, EGL_NONE };

            EGLContext context = egl.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT,
                    mGlVersion != 0 ? attribs : null);
            if (context == null || context == EGL_NO_CONTEXT) {
                //noinspection ConstantConditions
                throw new IllegalStateException(
                        "Could not create an EGL context. eglCreateContext failed with error: " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            }
            return context;
        }

        @Override
        void destroy(boolean full) {
            if (full && mCanvas != null) {
                mCanvas = null;
            }

            if (!isEnabled() || mDestroyed) {
                setEnabled(false);
                return;
            }

            destroySurface();
            setEnabled(false);

            mDestroyed = true;
            mGl = null;
        }

        void destroySurface() {
            if (mEglSurface != null && mEglSurface != EGL_NO_SURFACE) {
                sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
            }
        }

        @Override
        void invalidate(Surface surface) {
            // Cancels any existing buffer to ensure we'll get a buffer
            // of the right size before we call eglSwapBuffers
            sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

            if (mEglSurface != null && mEglSurface != EGL_NO_SURFACE) {
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
                setEnabled(false);
            }

            if (surface.isValid()) {
                if (!createSurface(surface)) {
                    return;
                }

                mUpdateDirtyRegions = true;

                if (mCanvas != null) {
                    setEnabled(true);
                }
            }
        }

        private boolean createSurface(Surface surface) {
            mEglSurface = sEgl.eglCreateWindowSurface(sEglDisplay, sEglConfig, surface, null);

            if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
                int error = sEgl.eglGetError();
                if (error == EGL_BAD_NATIVE_WINDOW) {
                    Log.e(LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return false;
                }
                throw new RuntimeException("createWindowSurface failed "
                        + GLUtils.getEGLErrorString(error));
            }

            if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new IllegalStateException("eglMakeCurrent failed " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            }

            enableDirtyRegions();

            return true;
        }

        @Override
        boolean validate() {
            return checkCurrent() != SURFACE_STATE_ERROR;
        }

        @Override
        void setup(int width, int height) {
            if (validate()) {
                mCanvas.setViewport(width, height);
                mWidth = width;
                mHeight = height;
            }
        }

        @Override
        int getWidth() {
            return mWidth;
        }

        @Override
        int getHeight() {
            return mHeight;
        }

        @Override
        HardwareCanvas getCanvas() {
            return mCanvas;
        }

        boolean canDraw() {
            return mGl != null && mCanvas != null;
        }        
        
        int onPreDraw(Rect dirty) {
            return DisplayList.STATUS_DONE;
        }

        void onPostDraw() {
        }

        class FunctorsRunnable implements Runnable {
            View.AttachInfo attachInfo;

            @Override
            public void run() {
                final HardwareRenderer renderer = attachInfo.mHardwareRenderer;
                if (renderer == null || !renderer.isEnabled() || renderer != GlRenderer.this) {
                    return;
                }

                final int surfaceState = checkCurrent();
                if (surfaceState != SURFACE_STATE_ERROR) {
                    int status = mCanvas.invokeFunctors(mRedrawClip);
                    handleFunctorStatus(attachInfo, status);
                }
            }
        }

        @Override
        boolean draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
                Rect dirty) {
            if (canDraw()) {
                if (!hasDirtyRegions()) {
                    dirty = null;
                }
                attachInfo.mIgnoreDirtyState = true;
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();

                view.mPrivateFlags |= View.PFLAG_DRAWN;

                final int surfaceState = checkCurrent();
                if (surfaceState != SURFACE_STATE_ERROR) {
                    HardwareCanvas canvas = mCanvas;
                    attachInfo.mHardwareCanvas = canvas;

                    if (mProfileEnabled) {
                        mProfileLock.lock();
                    }

                    dirty = beginFrame(canvas, dirty, surfaceState);

                    int saveCount = 0;
                    int status = DisplayList.STATUS_DONE;

                    try {
                        view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                                == View.PFLAG_INVALIDATED;
                        view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;

                        long buildDisplayListStartTime = startBuildDisplayListProfiling();
                        canvas.clearLayerUpdates();

                        DisplayList displayList = buildDisplayList(view);
                        status = prepareFrame(dirty);

                        saveCount = canvas.save();
                        callbacks.onHardwarePreDraw(canvas);

                        endBuildDisplayListProfiling(buildDisplayListStartTime);

                        if (displayList != null) {
                            status = drawDisplayList(attachInfo, canvas, displayList, status);
                        } else {
                            // Shouldn't reach here
                            view.draw(canvas);
                        }
                    } finally {
                        callbacks.onHardwarePostDraw(canvas);
                        canvas.restoreToCount(saveCount);
                        view.mRecreateDisplayList = false;

                        mFrameCount++;

                        debugDirtyRegions(dirty, canvas);
                        drawProfileData(attachInfo);
                    }

                    onPostDraw();

                    swapBuffers(status);

                    if (mProfileEnabled) {
                        mProfileLock.unlock();
                    }

                    attachInfo.mIgnoreDirtyState = false;
                    return dirty == null;
                }
            }

            return false;
        }

        abstract void drawProfileData(View.AttachInfo attachInfo);

        private Rect beginFrame(HardwareCanvas canvas, Rect dirty, int surfaceState) {
            // We had to change the current surface and/or context, redraw everything
            if (surfaceState == SURFACE_STATE_UPDATED) {
                dirty = null;
                beginFrame(null);
            } else {
                int[] size = mSurfaceSize;
                beginFrame(size);

                if (size[1] != mHeight || size[0] != mWidth) {
                    mWidth = size[0];
                    mHeight = size[1];

                    canvas.setViewport(mWidth, mHeight);

                    dirty = null;
                }
            }

            if (mProfileEnabled && mProfileVisualizerEnabled) dirty = null;

            return dirty;
        }

        private long startBuildDisplayListProfiling() {
            if (mProfileEnabled) {
                mProfileCurrentFrame += PROFILE_FRAME_DATA_COUNT;
                if (mProfileCurrentFrame >= mProfileData.length) {
                    mProfileCurrentFrame = 0;
                }

                return System.nanoTime();
            }
            return 0;
        }

        private void endBuildDisplayListProfiling(long getDisplayListStartTime) {
            if (mProfileEnabled) {
                long now = System.nanoTime();
                float total = (now - getDisplayListStartTime) * 0.000001f;
                //noinspection PointlessArithmeticExpression
                mProfileData[mProfileCurrentFrame] = total;
            }
        }

        private static DisplayList buildDisplayList(View view) {
            DisplayList displayList;
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "getDisplayList");
            try {
                displayList = view.getDisplayList();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return displayList;
        }

        private int prepareFrame(Rect dirty) {
            int status;
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "prepareFrame");
            try {
                status = onPreDraw(dirty);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return status;
        }

        private int drawDisplayList(View.AttachInfo attachInfo, HardwareCanvas canvas,
                DisplayList displayList, int status) {

            long drawDisplayListStartTime = 0;
            if (mProfileEnabled) {
                drawDisplayListStartTime = System.nanoTime();
            }

            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "drawDisplayList");
            try {
                status |= canvas.drawDisplayList(displayList, mRedrawClip,
                        DisplayList.FLAG_CLIP_CHILDREN);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }

            if (mProfileEnabled) {
                long now = System.nanoTime();
                float total = (now - drawDisplayListStartTime) * 0.000001f;
                mProfileData[mProfileCurrentFrame + 1] = total;
            }

            handleFunctorStatus(attachInfo, status);
            return status;
        }

        private void swapBuffers(int status) {
            if ((status & DisplayList.STATUS_DREW) == DisplayList.STATUS_DREW) {
                long eglSwapBuffersStartTime = 0;
                if (mProfileEnabled) {
                    eglSwapBuffersStartTime = System.nanoTime();
                }

                sEgl.eglSwapBuffers(sEglDisplay, mEglSurface);

                if (mProfileEnabled) {
                    long now = System.nanoTime();
                    float total = (now - eglSwapBuffersStartTime) * 0.000001f;
                    mProfileData[mProfileCurrentFrame + 2] = total;
                }

                checkEglErrors();
            }
        }

        private void debugDirtyRegions(Rect dirty, HardwareCanvas canvas) {
            if (mDebugDirtyRegions) {
                if (mDebugPaint == null) {
                    mDebugPaint = new Paint();
                    mDebugPaint.setColor(0x7fff0000);
                }

                if (dirty != null && (mFrameCount & 1) == 0) {
                    canvas.drawRect(dirty, mDebugPaint);
                }
            }
        }

        private void handleFunctorStatus(View.AttachInfo attachInfo, int status) {
            // If the draw flag is set, functors will be invoked while executing
            // the tree of display lists
            if ((status & DisplayList.STATUS_DRAW) != 0) {
                if (mRedrawClip.isEmpty()) {
                    attachInfo.mViewRootImpl.invalidate();
                } else {
                    attachInfo.mViewRootImpl.invalidateChildInParent(null, mRedrawClip);
                    mRedrawClip.setEmpty();
                }
            }

            if ((status & DisplayList.STATUS_INVOKE) != 0 ||
                    attachInfo.mHandler.hasCallbacks(mFunctorsRunnable)) {
                attachInfo.mHandler.removeCallbacks(mFunctorsRunnable);
                mFunctorsRunnable.attachInfo = attachInfo;
                attachInfo.mHandler.postDelayed(mFunctorsRunnable, FUNCTOR_PROCESS_DELAY);
            }
        }

        @Override
        void detachFunctor(int functor) {
            if (mCanvas != null) {
                mCanvas.detachFunctor(functor);
            }
        }

        @Override
        boolean attachFunctor(View.AttachInfo attachInfo, int functor) {
            if (mCanvas != null) {
                mCanvas.attachFunctor(functor);
                mFunctorsRunnable.attachInfo = attachInfo;
                attachInfo.mHandler.removeCallbacks(mFunctorsRunnable);
                attachInfo.mHandler.postDelayed(mFunctorsRunnable,  0);
                return true;
            }
            return false;
        }

        /**
         * Ensures the current EGL context is the one we expect.
         * 
         * @return {@link #SURFACE_STATE_ERROR} if the correct EGL context cannot be made current,
         *         {@link #SURFACE_STATE_UPDATED} if the EGL context was changed or
         *         {@link #SURFACE_STATE_SUCCESS} if the EGL context was the correct one
         */
        int checkCurrent() {
            if (mEglThread != Thread.currentThread()) {
                throw new IllegalStateException("Hardware acceleration can only be used with a " +
                        "single UI thread.\nOriginal thread: " + mEglThread + "\n" +
                        "Current thread: " + Thread.currentThread());
            }

            if (!mEglContext.equals(sEgl.eglGetCurrentContext()) ||
                    !mEglSurface.equals(sEgl.eglGetCurrentSurface(EGL_DRAW))) {
                if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                    Log.e(LOG_TAG, "eglMakeCurrent failed " +
                            GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    fallback(true);
                    return SURFACE_STATE_ERROR;
                } else {
                    if (mUpdateDirtyRegions) {
                        enableDirtyRegions();
                        mUpdateDirtyRegions = false;
                    }
                    return SURFACE_STATE_UPDATED;
                }
            }
            return SURFACE_STATE_SUCCESS;
        }
    }

    /**
     * Hardware renderer using OpenGL ES 2.0.
     */
    static class Gl20Renderer extends GlRenderer {
        // TODO: Convert dimensions to dp instead of px
        private static final int PROFILE_DRAW_MARGIN = 0;
        private static final int PROFILE_DRAW_WIDTH = 3;
        private static final int[] PROFILE_DRAW_COLORS = { 0xcf3e66cc, 0xcfdc3912, 0xcfe69800 };
        private static final int PROFILE_DRAW_CURRENT_FRAME_COLOR = 0xcf5faa4d;
        private static final int PROFILE_DRAW_THRESHOLD_COLOR = 0xff5faa4d;
        private static final int PROFILE_DRAW_THRESHOLD_STROKE_WIDTH = 2;
        private static final int PROFILE_DRAW_PX_PER_MS = 7;

        private GLES20Canvas mGlCanvas;

        private DisplayMetrics mDisplayMetrics;

        private static EGLSurface sPbuffer;
        private static final Object[] sPbufferLock = new Object[0];

        static class Gl20RendererEglContext extends ManagedEGLContext {
            final Handler mHandler = new Handler();

            public Gl20RendererEglContext(EGLContext context) {
                super(context);
            }

            @Override
            public void onTerminate(final EGLContext eglContext) {
                // Make sure we do this on the correct thread.
                if (mHandler.getLooper() != Looper.myLooper()) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onTerminate(eglContext);
                        }
                    });
                    return;
                }

                synchronized (sEglLock) {
                    if (sEgl == null) return;

                    if (EGLImpl.getInitCount(sEglDisplay) == 1) {
                        usePbufferSurface(eglContext);
                        GLES20Canvas.terminateCaches();

                        sEgl.eglDestroyContext(sEglDisplay, eglContext);
                        sEglContextStorage.set(null);
                        sEglContextStorage.remove();

                        sEgl.eglDestroySurface(sEglDisplay, sPbuffer);
                        sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE,
                                EGL_NO_SURFACE, EGL_NO_CONTEXT);

                        sEgl.eglReleaseThread();
                        sEgl.eglTerminate(sEglDisplay);

                        sEgl = null;
                        sEglDisplay = null;
                        sEglConfig = null;
                        sPbuffer = null;
                    }
                }
            }
        }

        Gl20Renderer(boolean translucent) {
            super(2, translucent);
        }

        @Override
        HardwareCanvas createCanvas() {
            return mGlCanvas = new GLES20Canvas(mTranslucent);
        }

        @Override
        ManagedEGLContext createManagedContext(EGLContext eglContext) {
            return new Gl20Renderer.Gl20RendererEglContext(mEglContext);
        }

        @Override
        int[] getConfig(boolean dirtyRegions) {
            return new int[] {
                    EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 0,
                    EGL_CONFIG_CAVEAT, EGL_NONE,
                    // TODO: Find a better way to choose the stencil size
                    EGL_STENCIL_SIZE, mShowOverdraw ? GLES20Canvas.getStencilSize() : 0,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT |
                            (dirtyRegions ? EGL14.EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0),
                    EGL_NONE
            };
        }
        
        @Override
        void initCaches() {
            GLES20Canvas.initCaches();
        }

        @Override
        boolean canDraw() {
            return super.canDraw() && mGlCanvas != null;
        }                

        @Override
        int onPreDraw(Rect dirty) {
            return mGlCanvas.onPreDraw(dirty);
        }

        @Override
        void onPostDraw() {
            mGlCanvas.onPostDraw();
        }

        @Override
        void drawProfileData(View.AttachInfo attachInfo) {
            if (mProfileEnabled && mProfileVisualizerEnabled) {
                initProfileDrawData(attachInfo);

                final int pxPerMs = (int) (PROFILE_DRAW_PX_PER_MS * mDisplayMetrics.density + 0.5f);
                final int margin = (int) (PROFILE_DRAW_MARGIN * mDisplayMetrics.density + 0.5f);
                final int width = (int) (PROFILE_DRAW_WIDTH * mDisplayMetrics.density + 0.5f);

                int x = 0;
                int count = 0;
                int current = 0;

                for (int i = 0; i < mProfileData.length; i += PROFILE_FRAME_DATA_COUNT) {
                    if (mProfileData[i] < 0.0f) break;

                    int index = count * 4;
                    if (i == mProfileCurrentFrame) current = index;

                    x += margin;
                    int x2 = x + width;

                    int y2 = mHeight;
                    int y1 = (int) (y2 - mProfileData[i] * pxPerMs);

                    float[] r = mProfileRects[0];
                    r[index] = x;
                    r[index + 1] = y1;
                    r[index + 2] = x2;
                    r[index + 3] = y2;

                    y2 = y1;
                    y1 = (int) (y2 - mProfileData[i + 1] * pxPerMs);

                    r = mProfileRects[1];
                    r[index] = x;
                    r[index + 1] = y1;
                    r[index + 2] = x2;
                    r[index + 3] = y2;

                    y2 = y1;
                    y1 = (int) (y2 - mProfileData[i + 2] * pxPerMs);

                    r = mProfileRects[2];
                    r[index] = x;
                    r[index + 1] = y1;
                    r[index + 2] = x2;
                    r[index + 3] = y2;

                    x += width;

                    count++;
                }
                x += margin;

                drawGraph(count);
                drawCurrentFrame(current);
                drawThreshold(x, pxPerMs);
            }
        }

        private void drawGraph(int count) {
            for (int i = 0; i < mProfileRects.length; i++) {
                mProfilePaint.setColor(PROFILE_DRAW_COLORS[i]);
                mGlCanvas.drawRects(mProfileRects[i], count, mProfilePaint);
            }
        }

        private void drawCurrentFrame(int index) {
            mProfilePaint.setColor(PROFILE_DRAW_CURRENT_FRAME_COLOR);
            mGlCanvas.drawRect(mProfileRects[2][index], mProfileRects[2][index + 1],
                    mProfileRects[2][index + 2], mProfileRects[0][index + 3], mProfilePaint);
        }

        private void drawThreshold(int x, int pxPerMs) {
            mProfilePaint.setColor(PROFILE_DRAW_THRESHOLD_COLOR);
            mProfilePaint.setStrokeWidth((int)
                    (PROFILE_DRAW_THRESHOLD_STROKE_WIDTH * mDisplayMetrics.density + 0.5f));
            int y = mHeight - 16 * pxPerMs;
            mGlCanvas.drawLine(0.0f, y, x, y, mProfilePaint);
            mProfilePaint.setStrokeWidth(1.0f);
        }

        private void initProfileDrawData(View.AttachInfo attachInfo) {
            if (mProfileRects == null) {
                mProfileRects = new float[PROFILE_FRAME_DATA_COUNT][];
                for (int i = 0; i < mProfileRects.length; i++) {
                    int count = mProfileData.length / PROFILE_FRAME_DATA_COUNT;
                    mProfileRects[i] = new float[count * 4];
                }
                mProfilePaint = new Paint();
            }

            if (mDisplayMetrics == null) {
                mDisplayMetrics = new DisplayMetrics();
            }
            attachInfo.mDisplay.getMetrics(mDisplayMetrics);
        }

        @Override
        void destroy(boolean full) {
            try {
                super.destroy(full);
            } finally {
                if (full && mGlCanvas != null) {
                    mGlCanvas = null;
                }
            }
        }

        @Override
        void pushLayerUpdate(HardwareLayer layer) {
            mGlCanvas.pushLayerUpdate(layer);
        }

        @Override
        public DisplayList createDisplayList(String name) {
            return new GLES20DisplayList(name);
        }

        @Override
        HardwareLayer createHardwareLayer(boolean isOpaque) {
            return new GLES20TextureLayer(isOpaque);
        }

        @Override
        HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
            return new GLES20RenderLayer(width, height, isOpaque);
        }

        @Override
        SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
            return ((GLES20TextureLayer) layer).getSurfaceTexture();
        }

        @Override
        void setSurfaceTexture(HardwareLayer layer, SurfaceTexture surfaceTexture) {
            ((GLES20TextureLayer) layer).setSurfaceTexture(surfaceTexture);
        }

        @Override
        boolean safelyRun(Runnable action) {
            boolean needsContext = true;
            if (isEnabled() && checkCurrent() != SURFACE_STATE_ERROR) needsContext = false;

            if (needsContext) {
                Gl20RendererEglContext managedContext =
                        (Gl20RendererEglContext) sEglContextStorage.get();
                if (managedContext == null) return false;
                usePbufferSurface(managedContext.getContext());
            }

            try {
                action.run();
            } finally {
                if (needsContext) {
                    sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE,
                            EGL_NO_SURFACE, EGL_NO_CONTEXT);
                }
            }

            return true;
        }

        @Override
        void destroyLayers(final View view) {
            if (view != null) {
                safelyRun(new Runnable() {
                    @Override
                    public void run() {
                        if (mCanvas != null) {
                            mCanvas.clearLayerUpdates();
                        }
                        destroyHardwareLayer(view);
                        GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
                    }
                });
            }
        }

        private static void destroyHardwareLayer(View view) {
            view.destroyLayer(true);

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;

                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    destroyHardwareLayer(group.getChildAt(i));
                }
            }
        }

        @Override
        void destroyHardwareResources(final View view) {
            if (view != null) {
                safelyRun(new Runnable() {
                    @Override
                    public void run() {
                        if (mCanvas != null) {
                            mCanvas.clearLayerUpdates();
                        }
                        destroyResources(view);
                        GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
                    }
                });
            }
        }

        private static void destroyResources(View view) {
            view.destroyHardwareResources();

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;

                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    destroyResources(group.getChildAt(i));
                }
            }
        }

        static HardwareRenderer create(boolean translucent) {
            if (GLES20Canvas.isAvailable()) {
                return new Gl20Renderer(translucent);
            }
            return null;
        }

        static void startTrimMemory(int level) {
            if (sEgl == null || sEglConfig == null) return;

            Gl20RendererEglContext managedContext =
                    (Gl20RendererEglContext) sEglContextStorage.get();
            // We do not have OpenGL objects
            if (managedContext == null) {
                return;
            } else {
                usePbufferSurface(managedContext.getContext());
            }

            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_FULL);
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_MODERATE);
            }
        }

        static void endTrimMemory() {
            if (sEgl != null && sEglDisplay != null) {
                sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
        }

        private static void usePbufferSurface(EGLContext eglContext) {
            synchronized (sPbufferLock) {
                // Create a temporary 1x1 pbuffer so we have a context
                // to clear our OpenGL objects
                if (sPbuffer == null) {
                    sPbuffer = sEgl.eglCreatePbufferSurface(sEglDisplay, sEglConfig, new int[] {
                            EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE
                    });
                }
            }
            sEgl.eglMakeCurrent(sEglDisplay, sPbuffer, sPbuffer, eglContext);
        }
    }
}
