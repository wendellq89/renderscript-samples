/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.example.rsmigration

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.*
import kotlin.math.*

/**
 * use Intrinsics RenderScript
 */
class RenderScriptImageProcessor(context: Context) : ImageProcessor {
    override val name = "RenderScript Intrinsics"

    // Renderscript scripts
    private val mRS: RenderScript = RenderScript.create(context)
    private val mIntrinsicColorMatrix = ScriptIntrinsicColorMatrix.create(mRS)
    private val mIntrinsicBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS))

    // Input image
    private lateinit var mInAllocation: Allocation

    // Intermediate buffers for the two-pass gaussian blur script
    private lateinit var mTempAllocations: Array<Allocation>

    // Output images
    private lateinit var mOutputImages: Array<Bitmap>
    private lateinit var mOutAllocations: Array<Allocation>


    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        if (numberOfOutputImages <= 0) {
            throw RuntimeException("Invalid number of output images: $numberOfOutputImages")
        }

        // Input allocation
        mInAllocation = Allocation.createFromBitmap(mRS, inputImage)

        // This buffer is only used as the intermediate result in script blur,
        // so only USAGE_SCRIPT is needed.
        mTempAllocations = Allocation.createAllocations(
            mRS,
            Type.createXY(mRS, Element.F32_4(mRS), inputImage.width, inputImage.height),
            Allocation.USAGE_SCRIPT,
            /*numAlloc=*/2
        )

        // Output images and allocations
        mOutputImages = Array(numberOfOutputImages) {
            Bitmap.createBitmap(inputImage.width, inputImage.height, inputImage.config)
        }
        mOutAllocations =
            Array(numberOfOutputImages) { i -> Allocation.createFromBitmap(mRS, mOutputImages[i]) }
    }

    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        // Set HUE rotation matrix
        // The matrix below performs a combined operation of,
        // RGB->HSV transform * HUE rotation * HSV->RGB transform
        val cos = cos(radian.toDouble())
        val sin = sin(radian.toDouble())
        val mat = Matrix3f()
        mat[0, 0] = (.299 + .701 * cos + .168 * sin).toFloat()
        mat[1, 0] = (.587 - .587 * cos + .330 * sin).toFloat()
        mat[2, 0] = (.114 - .114 * cos - .497 * sin).toFloat()
        mat[0, 1] = (.299 - .299 * cos - .328 * sin).toFloat()
        mat[1, 1] = (.587 + .413 * cos + .035 * sin).toFloat()
        mat[2, 1] = (.114 - .114 * cos + .292 * sin).toFloat()
        mat[0, 2] = (.299 - .300 * cos + 1.25 * sin).toFloat()
        mat[1, 2] = (.587 - .588 * cos - 1.05 * sin).toFloat()
        mat[2, 2] = (.114 + .886 * cos - .203 * sin).toFloat()

        // Invoke filter kernel
        mIntrinsicColorMatrix.setColorMatrix(mat)
        mIntrinsicColorMatrix.forEach(mInAllocation, mOutAllocations[outputIndex])

        // Copy to bitmap, this should cause a synchronization rather than a full copy.
        mOutAllocations[outputIndex].copyTo(mOutputImages[outputIndex])
        return mOutputImages[outputIndex]
    }

    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        if (radius < 1.0f || radius > 25.0f) {
            throw RuntimeException("Invalid radius ${radius}, must be within [1.0, 25.0]")
        }
        // Set blur kernel size
        mIntrinsicBlur.setRadius(radius)

        // Invoke filter kernel
        mIntrinsicBlur.setInput(mInAllocation)
        mIntrinsicBlur.forEach(mOutAllocations[outputIndex])

        // Copy to bitmap, this should cause a synchronization rather than a full copy.
        mOutAllocations[outputIndex].copyTo(mOutputImages[outputIndex])
        return mOutputImages[outputIndex]
    }

    override fun cleanup() {}
}
