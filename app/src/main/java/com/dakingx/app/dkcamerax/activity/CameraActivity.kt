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
import com.dakingx.dkcamerax.fragment.*
import com.dakingx.dkpreview.fragment.PreviewImageFragment
import com.dakingx.dkpreview.fragment.PreviewVideoFragment
import kotlinx.android.synthetic.main.activity_camera.*

class CameraActivity : AppCompatActivity(), CameraFragmentListener {

    companion object {
        fun start(context: Context, cameraDirection: CameraDirection) =
            context.startActivity(
                Intent(context, CameraActivity::class.java).apply {
                    putExtra(ARG_CAMERA_DIRECTION, cameraDirection.code)
                })

        private const val ARG_CAMERA_DIRECTION = "arg_camera_direction"
    }

    private lateinit var cameraFragment: CameraFragment
    private lateinit var previewImageFragment: PreviewImageFragment
    private lateinit var previewVideoFragment: PreviewVideoFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        val cameraDirection = CameraDirection.generateByCode(
            intent.getIntExtra(
                ARG_CAMERA_DIRECTION,
                CameraDirection.Front.code
            )
        )

        cameraFragment = CameraFragment.newInstance(getFileProviderAuthority(), cameraDirection)
        previewImageFragment = PreviewImageFragment()
        previewVideoFragment = PreviewVideoFragment()

        supportFragmentManager.beginTransaction()
            .add(R.id.container_preview_image, previewImageFragment)
            .add(R.id.container_preview_video, previewVideoFragment)
            .add(R.id.container_camera, cameraFragment)
            .hide(previewImageFragment)
            .hide(previewVideoFragment)
            .commitNow()

        takePictureBtn.setOnClickListener {
            takePictureBtn.visibility = View.GONE
            startRecordingBtn.visibility = View.GONE

            cameraFragment.takePicture()
        }

        startRecordingBtn.setOnClickListener {
            takePictureBtn.visibility = View.GONE
            startRecordingBtn.visibility = View.GONE
            stopRecodingBtn.visibility = View.VISIBLE

            cameraFragment.startRecording()
        }

        stopRecodingBtn.setOnClickListener {
            stopRecodingBtn.visibility = View.GONE

            cameraFragment.stopRecording()
        }
    }

    private fun setImageUri(uri: Uri) {
        runOnUiThread {
            supportFragmentManager.beginTransaction().show(previewImageFragment).commitNow()
            previewImageFragment.setImageURI(uri)
        }
    }

    private fun setVideoURI(uri: Uri) {
        runOnUiThread {
            supportFragmentManager.beginTransaction().show(previewVideoFragment).commitNow()
            previewVideoFragment.setVideoURI(uri)
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
            is CameraOpResult.Failure -> Toast.makeText(this, result.msg, Toast.LENGTH_SHORT).show()
        }

        runOnUiThread {
            takePictureBtn.visibility = View.VISIBLE
            startRecordingBtn.visibility = View.VISIBLE
            stopRecodingBtn.visibility = View.GONE
        }
    }

    override fun analyseImage(proxy: ImageProxy) {

    }
}
