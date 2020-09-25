package com.dakingx.dkpreview.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dakingx.dkpreview.R
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import kotlinx.android.synthetic.main.fragment_preview_video.*

class PreviewVideoFragment : BaseFragment() {

    companion object {
        const val ARG_VIDEO_URI = "arg_video_uri"
    }

    private var videoUri: Uri = Uri.EMPTY

    private var player: SimpleExoPlayer? = null

    private val mediaSourceFactory: ProgressiveMediaSource.Factory by lazy {
        val ctx = requireContext()
        val dataSourceFactory = DefaultDataSourceFactory(ctx, ctx.packageName, null)
        ProgressiveMediaSource.Factory(dataSourceFactory)
    }

    private var curWindowIndex = 0
    private var curPosition: Long = 0

    override fun restoreState(bundle: Bundle?) {
        bundle?.apply {
            getParcelable<Uri>(ARG_VIDEO_URI)?.let { videoUri = it }
        }
    }

    override fun storeState(bundle: Bundle) {
        bundle.also {
            it.putParcelable(ARG_VIDEO_URI, videoUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_preview_video, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        initializePlayer()

        setVideoURI(videoUri)
    }

    override fun onResume() {
        super.onResume()

        player?.playWhenReady = true
        player?.seekTo(curWindowIndex, curPosition)
    }

    override fun onPause() {
        curWindowIndex = player?.currentWindowIndex ?: 0
        curPosition = player?.currentPosition ?: 0
        player?.playWhenReady = false

        super.onPause()
    }

    override fun onDestroy() {
        releasePlayer()

        super.onDestroy()
    }

    fun setVideoURI(uri: Uri) {
        videoUri = uri

        playerView.post {
            player?.let {
                val mediaSource = mediaSourceFactory.createMediaSource(uri)
                it.prepare(mediaSource, true, false)
            }
        }
    }

    private fun initializePlayer() {
        if (player == null) {
            val ctx = requireContext()

            player = SimpleExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx))
                // 轨道选择器
                .setTrackSelector(DefaultTrackSelector(ctx))
                // 加载控制
                .setLoadControl(DefaultLoadControl.Builder().createDefaultLoadControl()).build()
        }

        playerView.player = player
    }

    private fun releasePlayer() {
        player?.let {
            it.release()
            player = null
        }
    }
}
