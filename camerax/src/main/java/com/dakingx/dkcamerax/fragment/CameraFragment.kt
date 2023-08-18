package com.dakingx.dkcamerax.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.dakingx.dkcamerax.R
import com.dakingx.dkcamerax.databinding.FragmentCameraBinding
import com.dakingx.dkcamerax.ext.checkAppPermission
import com.dakingx.dkcamerax.ext.filePath2Uri
import com.dakingx.dkcamerax.ext.generateTempFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// 摄像头方向
sealed class CameraDirection(val code: Int) {
    companion object {
        fun generateByCode(code: Int): CameraDirection = when (code) {
            Front.code -> Front
            Back.code -> Back
            else -> Any
        }
    }

    // 任意，优先选择前置
    object Any : CameraDirection(0)

    // 前置
    object Front : CameraDirection(1)

    // 后置
    object Back : CameraDirection(2)
}

// 摄像头状态
sealed class CameraState {
    // 空闲
    object Idle : CameraState()

    // 预览
    object Preview : CameraState()

    // 拍照
    object Image : CameraState()

    // 拍摄
    object Video : CameraState()
}

// 摄像头操作
sealed class CameraOp {
    // 拍照
    object Image : CameraOp()

    // 拍摄
    object Video : CameraOp()
}

// 摄像头操作结果
sealed class CameraOpResult {
    class Success(val op: CameraOp, val uri: Uri) : CameraOpResult()
    class Failure(val op: CameraOp, val msg: String) : CameraOpResult()
}

interface CameraFragmentListener {
    // 操作结果回调
    fun onOperationResult(result: CameraOpResult)

    // 分析图像
    fun analyseImage(proxy: ImageProxy)
}

class CameraFragment : BaseFragment() {
    companion object {
        /**
         * 根据系统版本返回相机、录音、写存储、读存储权限
         */
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        /**
         * 返回新的动态权限：相机、录音、写存储、读存储权限，如XXPermission
         */
        val REQUIRED_PERMISSIONS_TIRAMISU = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        @JvmStatic
        fun newInstance(fileProviderAuthority: String, cameraDirection: CameraDirection) =
            CameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PROVIDER_AUTH, fileProviderAuthority)
                    putInt(ARG_CAMERA_DIRECTION, cameraDirection.code)
                }
            }

        private const val ARG_FILE_PROVIDER_AUTH = "arg_file_provider_auth"
        private const val ARG_CAMERA_DIRECTION = "arg_camera_direction"

        private const val LENS_FACING_NULL = -1
        private const val LENS_FACING_FRONT = CameraSelector.LENS_FACING_FRONT
        private const val LENS_FACING_BACK = CameraSelector.LENS_FACING_BACK

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    private lateinit var mBinding: FragmentCameraBinding

    private var fileProviderAuthority: String = ""
    private var cameraDirection: Int = CameraDirection.Front.code

    var cameraListener: CameraFragmentListener? = null

    private val handler = Handler(Looper.getMainLooper())

    // 组件初始化
    @Volatile
    private var initialized = false

    // 宽高比
    private var screenAspectRatio: Int = AspectRatio.RATIO_16_9

    // 初始的旋转角度
    private var rotation = Surface.ROTATION_0

    // 摄像头方向
    private var lensFacing: Int = LENS_FACING_FRONT

    // 摄像头状态
    private val cameraState = AtomicReference<CameraState>(CameraState.Idle)

    private var executorService: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var cameraSelector: CameraSelector? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    override fun restoreState(bundle: Bundle?) {
        bundle?.apply {
            getString(ARG_FILE_PROVIDER_AUTH)?.let { fileProviderAuthority = it }
            cameraDirection = getInt(ARG_CAMERA_DIRECTION, CameraDirection.Front.code)
        }
    }

    override fun storeState(bundle: Bundle) {
        bundle.also {
            it.putString(ARG_FILE_PROVIDER_AUTH, fileProviderAuthority)
            it.putInt(ARG_CAMERA_DIRECTION, cameraDirection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (cameraListener == null) {
            cameraListener = context as? CameraFragmentListener
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        mBinding.previewView.post { setup() }
    }

    override fun onDestroyView() {
        clear()
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraListener = null
        super.onDestroy()
    }

    @SuppressLint("RestrictedApi")
    private fun clear() {
        handler.removeCallbacksAndMessages(null)

        try {
            if (initialized) {
                initialized = false

                preview?.setSurfaceProvider(null)

                when {
                    cameraState.get() == CameraState.Video -> {
                        videoCapture?.stopRecording()
                        videoCapture?.camera?.release()
                    }

                    cameraState.get() == CameraState.Image -> {
                        imageCapture?.camera?.release()
                    }

                    else -> {
                        preview?.camera?.release()
                    }
                }
                videoCapture = null
                imageCapture = null
                preview = null

                executorService?.shutdown()
                executorService = null

                imageAnalysis?.clearAnalyzer()
                imageAnalysis = null

                cameraProvider?.unbindAll()
                cameraProvider?.shutdown()
                cameraProvider = null
            }
        } catch (e: Throwable) {
        }
    }

    @SuppressLint("RestrictedApi")
    private fun setup() {
        if (!checkRequiredPermissions()) {
            toastError(R.string.tip_lack_required_permissions)
            return
        }

        clear()

        executorService = Executors.newSingleThreadExecutor()
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get()
        extensionsManager = ExtensionsManager.getInstance(requireContext()).get()

        // 摄像头方向
        when (CameraDirection.generateByCode(cameraDirection)) {
            CameraDirection.Front -> {
                if (!hasFrontCamera()) {
                    toastError(R.string.tip_other_app_hold_the_camera)
                    return
                }
                lensFacing = LENS_FACING_FRONT
            }

            CameraDirection.Back -> {
                if (!hasBackCamera()) {
                    toastError(R.string.tip_other_app_hold_the_camera)
                    return
                }
                lensFacing = LENS_FACING_BACK
            }

            CameraDirection.Any -> {
                lensFacing = getSuitableLensFacing()
                if (lensFacing == LENS_FACING_NULL) {
                    toastError(R.string.tip_other_app_hold_the_camera)
                    return
                }
            }
        }

        val displayMetrics = DisplayMetrics()
        mBinding.previewView.display.getRealMetrics(displayMetrics)
        // 宽高比
        screenAspectRatio =
            getSuitableAspectRatio(displayMetrics.widthPixels, displayMetrics.heightPixels)
        rotation = mBinding.previewView.display.rotation
        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = genPreviewBuilderWithExtenders().setTargetAspectRatio(screenAspectRatio)//宽高比
            .setTargetRotation(rotation)//初始的旋转角度
            .build()

        imageAnalysis = ImageAnalysis.Builder().setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation).build()

        imageAnalysis?.setAnalyzer(executorService!!) { imageProxy ->
            // 图片分析处理逻辑
            imageProxy.use { cameraListener?.analyseImage(it) }
        }

        initialized = true
        startPreview()
    }

    fun startPreview(force: Boolean = false): Boolean {
        return if (force || cameraState.compareAndSet(CameraState.Idle, CameraState.Preview)) {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                requireActivity(), cameraSelector!!, preview, imageAnalysis
            )
            preview?.setSurfaceProvider(mBinding.previewView.surfaceProvider)
            true
        } else {
            false
        }
    }

    fun takePicture(): Boolean {
        if (!initialized) { // 组件是否初始化
            toastError(R.string.tip_component_initialize_fail)
            return false
        }
        // 修改为拍照状态
        if (!cameraState.compareAndSet(CameraState.Preview, CameraState.Image)) {
            return false
        }

        imageCapture = genImageCaptureExtenderWithExtenders()
            // 优化捕获速度，可能降低图片质量
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 闪光模式
            .setFlashMode(ImageCapture.FLASH_MODE_OFF).setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation).build()

        cameraProvider?.run {
            unbindAll()
            bindToLifecycle(requireActivity(), cameraSelector!!, preview, imageCapture)
        }
        preview?.setSurfaceProvider(mBinding.previewView.surfaceProvider)

        val file = requireContext().generateTempFile("capture_image")
        if (file == null) {
            toastError(R.string.tip_gen_tmp_file_fail)
            cameraState.set(CameraState.Preview)
            return false
        }

        val metadata = ImageCapture.Metadata()  //控制前置摄像头拍照不镜像
        metadata.isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        val fileOptions = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()

        imageCapture?.takePicture(
            fileOptions,
            executorService!!,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (!initialized) return

                    val savedUri = outputFileResults.savedUri ?: filePath2Uri(file.absolutePath)

                    switchToPreviewState()

                    cameraListener?.onOperationResult(
                        if (savedUri != null) CameraOpResult.Success(CameraOp.Image, savedUri)
                        else CameraOpResult.Failure(
                            CameraOp.Image, getString(R.string.tip_output_uri_not_found)
                        )
                    )

                    imageCapture = null
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!initialized) return

                    val msg = String.format(
                        requireContext().getString(R.string.tip_capture_image_fail),
                        exception.toString()
                    )

                    switchToPreviewState()
                    cameraListener?.onOperationResult(CameraOpResult.Failure(CameraOp.Image, msg))
                    imageCapture = null
                }
            })

        return true
    }

    @SuppressLint("RestrictedApi", "MissingPermission")
    fun startRecording(): Boolean {
        // 组件是否初始化
        if (!initialized) {
            toastError(R.string.tip_component_initialize_fail)
            return false
        }
        // 修改为录制状态
        if (!cameraState.compareAndSet(CameraState.Preview, CameraState.Video)) {
            return false
        }

        videoCapture = VideoCapture.Builder().setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation).setVideoFrameRate(25)  // 视频帧率
            .setBitRate(1024 * 1024).build()    // bit率

        cameraProvider?.run {
            unbindAll()
            bindToLifecycle(requireActivity(), cameraSelector!!, preview, videoCapture)
        }

        preview?.setSurfaceProvider(mBinding.previewView.surfaceProvider)

        val file = requireContext().generateTempFile("capture_image", "mp4")
        if (file == null) {
            toastError(R.string.tip_gen_tmp_file_fail)
            cameraState.set(CameraState.Preview)
            return false
        }

        val fileOptions = VideoCapture.OutputFileOptions.Builder(file).build()

        mBinding.topProgressBar.visibility = View.VISIBLE

        videoCapture?.startRecording(
            fileOptions,
            executorService!!,
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    if (!initialized) return

                    val savedUri = outputFileResults.savedUri ?: filePath2Uri(file.absolutePath)
                    switchToPreviewState()

                    cameraListener?.onOperationResult(
                        if (savedUri != null) CameraOpResult.Success(CameraOp.Video, savedUri)
                        else CameraOpResult.Failure(
                            CameraOp.Video, getString(R.string.tip_output_uri_not_found)
                        )
                    )

                    videoCapture = null
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    if (!initialized) return

                    val msg = String.format(
                        requireContext().getString(R.string.tip_capture_video_fail), message
                    )

                    switchToPreviewState()
                    cameraListener?.onOperationResult(CameraOpResult.Failure(CameraOp.Video, msg))
                    videoCapture = null
                }
            })

        return true
    }

    @SuppressLint("RestrictedApi")
    fun stopRecording(): Boolean {
        if (!initialized) { // 组件是否初始化
            toastError(R.string.tip_component_initialize_fail)
            return false
        }
        // 是否处于录制状态
        if (cameraState.get() != CameraState.Video) {
            return false
        }

        videoCapture?.stopRecording()
        return true
    }

    private fun switchToPreviewState() {
        handler.post {
            mBinding.topProgressBar.visibility = View.GONE
        }

        cameraState.set(CameraState.Preview)
    }

    private fun genPreviewBuilderWithExtenders(): Preview.Builder {
        //ExtensionsManager API文档
        //https://developer.android.google.cn/reference/androidx/camera/extensions/ExtensionsManager
        //https://101.dev/t/camerax-extensions-api/470

        setExtension(ExtensionMode.AUTO)//自动
        setExtension(ExtensionMode.BOKEH)//焦外成像
        setExtension(ExtensionMode.HDR)//高动态范围
        setExtension(ExtensionMode.FACE_RETOUCH)//脸部照片修复
        setExtension(ExtensionMode.NIGHT)//夜间

        return Preview.Builder()
    }

    private fun setExtension(mode: Int) {
        if (extensionsManager!!.isExtensionAvailable(cameraProvider!!, cameraSelector!!, mode)) {
            cameraSelector = extensionsManager!!.getExtensionEnabledCameraSelector(
                cameraProvider!!, cameraSelector!!, mode
            )
        }
    }

    private fun genImageCaptureExtenderWithExtenders(): ImageCapture.Builder {
        setExtension(ExtensionMode.AUTO)//自动
        setExtension(ExtensionMode.BOKEH)//焦外成像
        setExtension(ExtensionMode.HDR)//高动态范围
        setExtension(ExtensionMode.FACE_RETOUCH)//脸部照片修复
        setExtension(ExtensionMode.NIGHT)//夜间

        return ImageCapture.Builder()
    }

    /**
     * 确定合适的宽高比
     */
    private fun getSuitableAspectRatio(previewWidth: Int, previewHeight: Int): Int {
        val previewRatio = max(previewWidth, previewHeight).toDouble() / min(
            previewWidth, previewHeight
        )
        return if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            AspectRatio.RATIO_4_3
        } else {
            AspectRatio.RATIO_16_9
        }
    }

    private fun getSuitableLensFacing() = when {
        hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
        hasBackCamera() -> CameraSelector.LENS_FACING_BACK
        else -> LENS_FACING_NULL
    }

    private fun hasFrontCamera() =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false

    private fun hasBackCamera() =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false

    private fun filePath2Uri(filePath: String): Uri? =
        context?.filePath2Uri(fileProviderAuthority, filePath)

    private fun checkRequiredPermissions(): Boolean =
        context?.checkAppPermission(*REQUIRED_PERMISSIONS.toTypedArray()) ?: false

    private fun toastError(@StringRes stringResId: Int) =
        Toast.makeText(requireContext(), getString(stringResId), Toast.LENGTH_SHORT).show()
}