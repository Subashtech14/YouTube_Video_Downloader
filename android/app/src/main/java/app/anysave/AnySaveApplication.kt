package app.anysave

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class AnySaveApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Engine.start(this)
        DownloadService.createChannels(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}
