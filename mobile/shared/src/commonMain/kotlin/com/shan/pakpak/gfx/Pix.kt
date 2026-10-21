package com.shan.pakpak.gfx

import androidx.compose.ui.graphics.ImageBitmap
import com.shan.pakpak.imageFromArgb

/** A CPU pixel buffer (ARGB ints) used to build sprites, converted to a platform image once. */
class Pix(val width: Int, val height: Int) {
    val data = IntArray(width * height)
    fun setPixel(x: Int, y: Int, c: Int) { if (x in 0 until width && y in 0 until height) data[y * width + x] = c }
    fun getPixel(x: Int, y: Int): Int = data[y * width + x]
    fun toImage(): ImageBitmap = imageFromArgb(width, height, data)
}
