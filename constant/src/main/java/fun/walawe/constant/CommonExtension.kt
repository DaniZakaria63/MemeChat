package `fun`.walawe.constant

fun Long?.orZero(): Long = this?:0L

fun Boolean?.orFalse(): Boolean = this?:false