package com.manga.translator.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.manga.translator.R
import com.manga.translator.ocr.OcrTranslationEngine
import com.manga.translator.ui.widget.TranslatedImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaPageAdapter(
    private val ocrEngine: OcrTranslationEngine,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<MangaPageAdapter.PageViewHolder>() {

    private val urls = mutableListOf<String>()
    private val ocrJobs = mutableMapOf<Int, Job>()

    fun setUrls(newUrls: List<String>) {
        urls.clear()
        urls.addAll(newUrls)
        notifyDataSetChanged()
    }

    fun toggleAllOverlays() {
        // Toggle is done per-view in ViewHolder; trigger a global flag instead
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(urls[position], position)
    }

    override fun getItemCount() = urls.size

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        ocrJobs[holder.adapterPosition]?.cancel()
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: TranslatedImageView = itemView.findViewById(R.id.translatedImageView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.pageProgressBar)
        private val ocrProgress: ProgressBar = itemView.findViewById(R.id.ocrProgressBar)

        fun bind(url: String, position: Int) {
            progressBar.visibility = View.VISIBLE
            ocrProgress.visibility = View.GONE
            imageView.setOverlays(emptyList())

            val glideUrl = GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader("Referer", "https://comic.naver.com")
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                    )
                    .build()
            )

            Glide.with(itemView.context)
                .asBitmap()
                .load(glideUrl)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any?,
                        target: Target<Bitmap>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE

                        if (ocrEngine.isReady) {
                            ocrProgress.visibility = View.VISIBLE
                            val job = scope.launch(Dispatchers.IO) {
                                val overlays = ocrEngine.processImage(resource)
                                withContext(Dispatchers.Main) {
                                    ocrProgress.visibility = View.GONE
                                    imageView.setOverlays(overlays)
                                }
                            }
                            ocrJobs[position] = job
                        }
                        return false
                    }
                })
                .into(imageView)
        }
    }
}
