package `fun`.walawe.vector

class NativeLib {

    /**
     * A native method that is implemented by the 'vector' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'vector' library on application startup.
        init {
            System.loadLibrary("vector")
        }
    }
}