/*
 * Copyright 2014 Google Inc. All rights reserved.
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

package com.github.teocci.libmediacodec.gles;

/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 */
public class FullFrameRect
{
    private final Drawable2d rectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
    private Texture2dProgram textureProgram;

    /**
     * Prepares the object.
     *
     * @param program The program to use.  FullFrameRect takes ownership, and will release
     *                the program when no longer needed.
     */
    public FullFrameRect(Texture2dProgram program)
    {
        textureProgram = program;
    }

    /**
     * Releases resources.
     * <p>
     * This must be called with the appropriate EGL context current (i.e. the one that was
     * current when the constructor was called).  If we're about to destroy the EGL context,
     * there's no value in having the caller make it current just to do this cleanup, so you
     * can pass a flag that will tell this function to skip any EGL-context-specific cleanup.
     */
    public void release(boolean doEglCleanup)
    {
        if (textureProgram != null) {
            if (doEglCleanup) {
                textureProgram.release();
            }
            textureProgram = null;
        }
    }

    /**
     * Returns the program currently in use.
     */
    public Texture2dProgram getProgram()
    {
        return textureProgram;
    }

    /**
     * Changes the program.  The previous program will be released.
     * <p>
     * The appropriate EGL context must be current.
     */
    public void changeProgram(Texture2dProgram program)
    {
        textureProgram.release();
        textureProgram = program;
    }

    /**
     * Creates a texture object suitable for use with drawFrame().
     */
    public int createTextureObject()
    {
        return textureProgram.createTextureObject();
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     */
    public void drawFrame(int textureId, float[] texMatrix)
    {
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
        textureProgram.draw(GlUtil.IDENTITY_MATRIX, rectDrawable.getVertexArray(), 0,
                rectDrawable.getVertexCount(), rectDrawable.getCoordsPerVertex(),
                rectDrawable.getVertexStride(),
                texMatrix, rectDrawable.getTexCoordArray(), textureId,
                rectDrawable.getTexCoordStride());
    }
}
