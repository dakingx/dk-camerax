package com.dakingx.dkcamerax.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.*
import androidx.camera.extensions.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.dakingx.dkcamerax.R
import com.dakingx.dkcamerax.ext.checkAppPermission
import com.dakingx.dkcamerax.ext.filePath2Uri
import com.dakingx.dkcamerax.ext.generateTempFile
import kotlinx.android.synthetic.main.fragment_camera.*
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

class CameraFragment : BaseFragment() {

    companion object {
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        @JvmStatic
        fun newInstance(fileProviderAuthority: String, cameraDirection: Int) =
            CameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PROVIDER_AUTH, fileProviderAuthority)
                    putInt(ARG_CAMERA_DIRECTION, cameraDirection)
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

    private var fileProviderAuthority: String = ""
    private var cameraDirection: Int = CameraDirection.Front.code

    private var listener: CameraFragmentListener? = null

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

    private lateinit var executorService: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraSelector: CameraSelector
    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture
    private lateinit var imageAnalysis: ImageAnalysis

    override fun restoreState(bundle: Bundle?) {
        bundle?.apply {
            getString(ARG_FILE_PROVIDER_AUTH)?.let {
                fileProviderAuthority = it
            }
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

        listener = context as? CameraFragmentListener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        previewView.post {
            setup()
        }
    }

    override fun onDestroy() {
        listener = null

        if (initialized) {
            executorService.shutdown()
        }
        initialized = false

        super.onDestroy()
    }

    @SuppressLint("RestrictedApi")
    private fun setup() {
        if (!checkRequiredPermissions()) {
            toastError(R.string.tip_lack_required_permissions)
            return
        }

        this.executorService = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        this.cameraProvider = cameraProviderFuture.get()

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
        previewView.display.getRealMetrics(displayMetrics)
        // 宽高比
        screenAspectRatio = getSuitableAspectRatio(
            displayMetrics.widthPixels, displayMetrics.heightPixels
        )

        rotation = previewView.display.rotation

        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = genPreviewBuilderWithExtenders(cameraSelector)
            // 宽高比
            .setTargetAspectRatio(screenAspectRatio)
            // 初始的旋转角度
            .setTargetRotation(rotation).build()

        imageAnalysis = ImageAnalysis.Builder().setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation).build()

        imageAnalysis.setAnalyzer(executorService, { imageProxy ->
            // 图片分析处理逻辑
            imageProxy.use {
                listener?.analyseImage(it)
            }
        })

        initialized = true

        startPreview()
    }

    fun startPreview(force: Boolean = false): Boolean {
        return if (force || cameraState.compareAndSet(CameraState.Idle, CameraState.Preview)) {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                requireActivity(), cameraSelector, preview, imageAnalysis
            )

            preview.setSurfaceProvider(previewView.surfaceProvider)

            true
        } else {
            false
        }
    }

    fun takePicture(): Boolean {
        // 组件是否初始化
        if (!initialized) {
            toastError(R.string.tip_component_initialize_fail)
            return false
        }
        // 修改为拍照状态
        if (!cameraState.compareAndSet(CameraState.Preview, CameraState.Image)) {
            return false
        }

        imageCapture = genImageCaptureExtenderWithExtenders(cameraSelector)
            // 优化捕获速度，可能降低图片质量
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 闪光模式
            .setFlashMode(ImageCapture.FLASH_MODE_OFF).setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation).build()

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(requireActivity(), cameraSelector, preview, imageCapture)

        preview.setSurfaceProvider(previewView.surfaceProvider)

        val file = requireContext().generateTempFile("capture_image")
        if (file == null) {
            toastError(R.string.tip_gen_tmp_file_fail)
            cameraState.set(CameraState.Preview)
            return false
        }

        val fileOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(fileOptions,
            executorService,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: filePath2Uri(file.absolutePath)

                    switchToPreviewState()

                    listener?.onOperationResult(
                        if (savedUri != null) CameraOpResult.Success(CameraOp.Image, savedUri)
                        else CameraOpResult.Failure(
                            CameraOp.Image,
                            getString(R.string.tip_output_uri_not_found)
                        )
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    val msg = String.format(
                        requireContext().getString(R.string.tip_capture_image_fail),
                        exception.toString()
                    )

                    switchToPreviewState()

                    listener?.onOperationResult(CameraOpResult.Failure(CameraOp.Image, msg))
                }
            })

        return true
    }

    @SuppressLint("RestrictedApi")
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
            .setTargetRotation(rotation)
            // 视频帧率
            .setVideoFrameRate(25)
            // bit率
            .setBitRate(1024 * 1024).build()

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(requireActivity(), cameraSelector, preview, videoCapture)

        preview.setSurfaceProvider(previewView.surfaceProvider)

        val file = requireContext().generateTempFile("capture_image", "mp4")
        if (file == null) {
            toastError(R.string.tip_gen_tmp_file_fail)
            cameraState.set(CameraState.Preview)
            return false
        }

        val fileOptions = VideoCapture.OutputFileOptions.Builder(file).build()

        topProgressBar.visibility = View.VISIBLE

        videoCapture.startRecording(fileOptions,
            executorService,
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: filePath2Uri(file.absolutePath)

                    switchToPreviewState()

                    listener?.onOperationResult(
                        if (savedUri != null) CameraOpResult.Success(CameraOp.Video, savedUri)
                        else CameraOpResult.Failure(
                            CameraOp.Video,
                            getString(R.string.tip_output_uri_not_found)
                        )
                    )
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    val msg = String.format(
                        requireContext().getString(R.string.tip_capture_video_fail), message
                    )

                    switchToPreviewState()

                    listener?.onOperationResult(CameraOpResult.Failure(CameraOp.Video, msg))
                }
            })

        return true
    }

    @SuppressLint("RestrictedApi")
    fun stopRecording(): Boolean {
        // 组件是否初始化
        if (!initialized) {
            toastError(R.string.tip_component_initialize_fail)
            return false
        }
        // 是否处于录制状态
        if (cameraState.get() != CameraState.Video) {
            return false
        }

        videoCapture.stopRecording()
        return true
    }

    private fun switchToPreviewState() {
        requireActivity().runOnUiThread {
            topProgressBar.visibility = View.GONE
        }

        cameraState.set(CameraState.Preview)
    }

    private fun genPreviewBuilderWithExtenders(cameraSelector: CameraSelector): Preview.Builder {
        val builder = Preview.Builder()

        val autoExtender = AutoPreviewExtender.create(builder)
        if (autoExtender.isExtensionAvailable(cameraSelector)) {
            autoExtender.enableExtension(cameraSelector)
        }
        val bokehExtender = BokehPreviewExtender.create(builder)
        if (bokehExtender.isExtensionAvailable(cameraSelector)) {
            bokehExtender.enableExtension(cameraSelector)
        }
        val hdrExtender = HdrPreviewExtender.create(builder)
        if (hdrExtender.isExtensionAvailable(cameraSelector)) {
            hdrExtender.enableExtension(cameraSelector)
        }
        val beautyExtender = BeautyPreviewExtender.create(builder)
        if (beautyExtender.isExtensionAvailable(cameraSelector)) {
            beautyExtender.enableExtension(cameraSelector)
        }
        val nightExtender = NightPreviewExtender.create(builder)
        if (nightExtender.isExtensionAvailable(cameraSelector)) {
            nightExtender.enableExtension(cameraSelector)
        }

        return builder
    }

    private fun genImageCaptureExtenderWithExtenders(cameraSelector: CameraSelector): ImageCapture.Builder {
        val builder = ImageCapture.Builder()

        val autoExtender = AutoImageCaptureExtender.create(builder)
        if (autoExtender.isExtensionAvailable(cameraSelector)) {
            autoExtender.enableExtension(cameraSelector)
        }
        val bokehExtender = BokehImageCaptureExtender.create(builder)
        if (bokehExtender.isExtensionAvailable(cameraSelector)) {
            bokehExtender.enableExtension(cameraSelector)
        }
        val hdrExtender = HdrImageCaptureExtender.create(builder)
        if (hdrExtender.isExtensionAvailable(cameraSelector)) {
            hdrExtender.enableExtension(cameraSelector)
        }
        val beautyExtender = BeautyImageCaptureExtender.create(builder)
        if (beautyExtender.isExtensionAvailable(cameraSelector)) {
            beautyExtender.enableExtension(cameraSelector)
        }
        val nightExtender = NightImageCaptureExtender.create(builder)
        if (nightExtender.isExtensionAvailable(cameraSelector)) {
            nightExtender.enableExtension(cameraSelector)
        }

        return builder
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

    private fun hasFrontCamera() = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

    private fun hasBackCamera() = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

    private fun filePath2Uri(filePath: String): Uri? =
        context?.filePath2Uri(fileProviderAuthority, filePath)

    private fun checkRequiredPermissions(): Boolean =
        context?.checkAppPermission(*REQUIRED_PERMISSIONS.toTypedArray()) ?: false

    private fun toastError(@StringRes stringResId: Int) =
        Toast.makeText(requireContext(), getString(stringResId), Toast.LENGTH_SHORT).show()
}

interface CameraFragmentListener {
    // 操作结果回调
    fun onOperationResult(result: CameraOpResult)

    // 分析图像
    fun analyseImage(proxy: ImageProxy)
}
