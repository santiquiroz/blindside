package io.github.santiquiroz.blindside.core.tracking

class Matrix(val rows: Int, val cols: Int, private val values: DoubleArray) {
    init {
        require(values.size == rows * cols) { "expected ${rows * cols} values, got ${values.size}" }
    }

    operator fun get(row: Int, col: Int): Double = values[row * cols + col]

    operator fun plus(other: Matrix): Matrix = elementwise(other) { a, b -> a + b }

    operator fun minus(other: Matrix): Matrix = elementwise(other) { a, b -> a - b }

    operator fun times(factor: Double): Matrix = Matrix(rows, cols, DoubleArray(values.size) { values[it] * factor })

    operator fun times(other: Matrix): Matrix {
        require(cols == other.rows) { "shape mismatch ${rows}x$cols * ${other.rows}x${other.cols}" }
        return build(rows, other.cols) { r, c -> (0 until cols).sumOf { k -> this[r, k] * other[k, c] } }
    }

    fun transpose(): Matrix = build(cols, rows) { r, c -> this[c, r] }

    fun inverse2x2(): Matrix {
        require(rows == 2 && cols == 2) { "inverse2x2 needs a 2x2 matrix" }
        val det = this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]
        return of(2, 2, this[1, 1] / det, -this[0, 1] / det, -this[1, 0] / det, this[0, 0] / det)
    }

    private fun elementwise(other: Matrix, op: (Double, Double) -> Double): Matrix {
        require(rows == other.rows && cols == other.cols) { "shape mismatch" }
        return Matrix(rows, cols, DoubleArray(values.size) { op(values[it], other.values[it]) })
    }

    companion object {
        fun of(rows: Int, cols: Int, vararg values: Double) = Matrix(rows, cols, values.copyOf())

        fun build(rows: Int, cols: Int, cell: (Int, Int) -> Double) =
            Matrix(rows, cols, DoubleArray(rows * cols) { cell(it / cols, it % cols) })

        fun identity(size: Int) = build(size, size) { r, c -> if (r == c) 1.0 else 0.0 }

        fun column(vararg values: Double) = Matrix(values.size, 1, values.copyOf())
    }
}
