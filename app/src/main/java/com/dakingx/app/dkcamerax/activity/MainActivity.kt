package com.dakingx.app.dkcamerax.activity

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dakingx.app.dkcamerax.R
import com.dakingx.dkcamerax.fragment.CameraDirection
import com.dakingx.dkcamerax.fragment.CameraFragment
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.activity_main.backCameraBtn
import kotlinx.android.synthetic.main.activity_main.frontCameraBtn

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frontCameraBtn.setOnClickListener {
            startCameraActivity(CameraDirection.Front)
        }

        backCameraBtn.setOnClickListener {
            startCameraActivity(CameraDirection.Back)
        }
    }

    private fun toast(stringResId: Int) {
        Toast.makeText(this, getString(stringResId), Toast.LENGTH_SHORT).show()
    }

    private fun startCameraActivity(cameraDirection: CameraDirection) {
        Dexter.withContext(this)
            .withPermissions(*CameraFragment.REQUIRED_PERMISSIONS.toTypedArray())
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        runOnUiThread {
                            CameraActivity.start(this@MainActivity, cameraDirection)
                        }
                    } else {
                        runOnUiThread {
                            toast(R.string.tip_lack_permission)
                        }
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    list: MutableList<PermissionRequest>, token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
    }
}
