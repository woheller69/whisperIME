package com.whispertflite

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.whispertflite.databinding.ActivityDownloadBinding
import com.whispertflite.utils.Downloader

class DownloadActivity  : AppCompatActivity() {
    private var binding: ActivityDownloadBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    override fun onResume() {
        super.onResume()
        if (Downloader.checkModels(this)){
            // call Main Activity
            binding?.downloadProgress?.setProgress(100)
            binding?.downloadProgress?.setVisibility(View.VISIBLE)
            binding?.buttonStart?.setVisibility(View.VISIBLE)
            if (!Downloader.checkUpdate(this)){
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                binding?.buttonUpdate?.setVisibility(View.VISIBLE)
            }
        }
    }

    fun download(view: View) {
        binding?.downloadSize?.setVisibility(View.VISIBLE)
        binding?.downloadProgress?.setVisibility(View.VISIBLE)
        binding?.buttonStart?.setVisibility(View.INVISIBLE)
        Downloader.downloadModels(this, binding)
    }

    fun startMain(view: View) {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    fun updateModels(view: View) {
        binding?.downloadSize?.setVisibility(View.VISIBLE)
        binding?.downloadProgress?.setVisibility(View.VISIBLE)
        binding?.buttonStart?.setVisibility(View.INVISIBLE)
        binding?.buttonUpdate?.setVisibility(View.GONE)
        Downloader.deleteOldModels(this);
        Downloader.downloadModels(this, binding)
    }
}
