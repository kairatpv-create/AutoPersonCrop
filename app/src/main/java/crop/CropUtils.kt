package com.example.a01.crop

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object CropUtils {

    fun cropPerson(
        bitmap: Bitmap,
        box: RectF
    ): Bitmap {

        var left =
            box.left.toInt()

        var top =
            box.top.toInt()

        var right =
            box.right.toInt()

        var bottom =
            box.bottom.toInt()

        left = max(0, left)
        top = max(0, top)

        right = min(bitmap.width, right)
        bottom = min(bitmap.height, bottom)

        if (right <= left)
            return bitmap

        if (bottom <= top)
            return bitmap

        val width =
            right - left

        val height =
            bottom - top

        if (width < 10)
            return bitmap

        if (height < 10)
            return bitmap

        return Bitmap.createBitmap(

            bitmap,

            left,

            top,

            width,

            height

        )

    }

}