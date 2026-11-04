package com.crsnow.neckscape.theory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT of complex data split into [re] and [im] (size a power of two); [inverse] includes the 1/n. */
fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
    val n = re.size
    require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }
    // Bit-reversed order, then butterflies of doubling span.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            re[i] = re[j].also { re[j] = re[i] }
            im[i] = im[j].also { im[j] = im[i] }
        }
    }
    val sign = if (inverse) 1 else -1
    var span = 1
    while (span < n) {
        val step = sign * PI / span
        for (k in 0 until span) {
            val wr = cos(step * k)
            val wi = sin(step * k)
            var a = k
            while (a < n) {
                val b = a + span
                val tr = wr * re[b] - wi * im[b]
                val ti = wr * im[b] + wi * re[b]
                re[b] = re[a] - tr
                im[b] = im[a] - ti
                re[a] += tr
                im[a] += ti
                a += 2 * span
            }
        }
        span *= 2
    }
    if (inverse) for (i in 0 until n) {
        re[i] /= n
        im[i] /= n
    }
}
