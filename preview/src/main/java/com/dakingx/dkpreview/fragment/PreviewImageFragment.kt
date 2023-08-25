package com.dakingx.dkpreview.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dakingx.dkpreview.R
import com.dakingx.dkpreview.databinding.FragmentPreviewImageBinding

class PreviewImageFragment : BaseFragment() {

    companion object {
        const val ARG_IMAGE_URI = "arg_image_uri"
    }

    private var imageUri: Uri = Uri.EMPTY

    private lateinit var mBinding: FragmentPreviewImageBinding

    override fun restoreState(bundle: Bundle?) {
        bundle?.apply {
            getParcelable<Uri>(ARG_IMAGE_URI)?.let { imageUri = it }
        }
    }

    override fun storeState(bundle: Bundle) {
        bundle.also {
            it.putParcelable(ARG_IMAGE_URI, imageUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentPreviewImageBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        setImageURI(imageUri)
    }

    fun setImageURI(uri: Uri) {
        imageUri = uri

        mBinding.photoView.post {
            val curUri = imageUri

            if (Uri.EMPTY == curUri) {
                mBinding.photoView.setImageResource(R.drawable.ic_image_24dp)
            } else {
                mBinding.photoView.setImageURI(curUri)
            }
        }
    }
}
