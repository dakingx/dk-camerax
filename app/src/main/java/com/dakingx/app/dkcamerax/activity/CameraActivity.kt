package com.dakingx.app.dkcamerax.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import com.dakingx.app.dkcamerax.R
import com.dakingx.app.dkcamerax.config.getFileProviderAuthority
import com.dakingx.app.dkcamerax.databinding.ActivityCameraBinding
import com.dakingx.dkcamerax.fragment.CameraDirection
import com.dakingx.dkcamerax.fragment.CameraFragment
import com.dakingx.dkcamerax.fragment.CameraFragmentListener
import com.dakingx.dkcamerax.fragment.CameraOp
import com.dakingx.dkcamerax.fragment.CameraOpResult
import com.dakingx.dkpreview.fragment.PreviewImageFragment
import com.dakingx.dkpreview.fragment.PreviewVideoFragment

class CameraActivity : AppCompatActivity(), CameraFragmentListener {

    private val mBinding by lazy { ActivityCameraBinding.inflate(layoutInflater) }

    companion object {
        fun start(context: Context, cameraDirection: CameraDirection) =
            context.startActivity(Intent(context, CameraActivity::class.java).apply {
                putExtra(ARG_CAMERA_DIRECTION, cameraDirection.code)
            })

        private const val ARG_CAMERA_DIRECTION = "arg_camera_direction"
    }

    private lateinit var cameraFragment: CameraFragment
    private lateinit var previewImageFragment: PreviewImageFragment
    private lateinit var previewVideoFragment: PreviewVideoFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)

        val cameraDirection = CameraDirection.generateByCode(
            intent.getIntExtra(ARG_CAMERA_DIRECTION, CameraDirection.Front.code)
        )

        cameraFragment = CameraFragment.newInstance(getFileProviderAuthority(), cameraDirection)
        previewImageFragment = PreviewImageFragment()
        previewVideoFragment = PreviewVideoFragment()

        supportFragmentManager.beginTransaction()
            .add(R.id.container_preview_image, previewImageFragment)
            .add(R.id.container_preview_video, previewVideoFragment)
            .add(R.id.container_camera, cameraFragment).hide(previewImageFragment)
            .hide(previewVideoFragment).commitNow()

        mBinding.takePictureBtn.setOnClickListener {
            mBinding.takePictureBtn.visibility = View.GONE
            mBinding.startRecordingBtn.visibility = View.GONE

            cameraFragment.takePicture()
        }

        mBinding.startRecordingBtn.setOnClickListener {
            mBinding.takePictureBtn.visibility = View.GONE
            mBinding.startRecordingBtn.visibility = View.GONE
            mBinding.stopRecodingBtn.visibility = View.VISIBLE

            cameraFragment.startRecording()
        }

        mBinding.stopRecodingBtn.setOnClickListener {
            mBinding.stopRecodingBtn.visibility = View.GONE

            cameraFragment.stopRecording()
        }
    }

    private fun setImageUri(uri: Uri) {
        runOnUiThread {
            if (!supportFragmentManager.isDestroyed) {
                supportFragmentManager.beginTransaction().show(previewImageFragment).commitNow()
                previewImageFragment.setImageURI(uri)
            }
        }
    }

    private fun setVideoURI(uri: Uri) {
        runOnUiThread {
            if (!supportFragmentManager.isDestroyed) {
                supportFragmentManager.beginTransaction().show(previewVideoFragment).commitNow()
                previewVideoFragment.setVideoURI(uri)
            }
        }
    }

    override fun onBackPressed() {
        if (previewImageFragment.isVisible) {
            supportFragmentManager.beginTransaction().hide(previewImageFragment).commitNow()
            return
        }
        if (previewVideoFragment.isVisible) {
            supportFragmentManager.beginTransaction().hide(previewVideoFragment).commitNow()
            return
        }
        super.onBackPressed()
    }

    override fun onOperationResult(result: CameraOpResult) {
        when (result) {
            is CameraOpResult.Success -> {
                when (result.op) {
                    CameraOp.Image -> setImageUri(result.uri)
                    CameraOp.Video -> setVideoURI(result.uri)
                }
            }

            is CameraOpResult.Failure -> runOnUiThread {
                Toast.makeText(this, result.msg, Toast.LENGTH_SHORT).show()
            }
        }

        runOnUiThread {
            mBinding.takePictureBtn.visibility = View.VISIBLE
            mBinding.startRecordingBtn.visibility = View.VISIBLE
            mBinding.stopRecodingBtn.visibility = View.GONE
        }
    }

    override fun analyseImage(proxy: ImageProxy) {}
}