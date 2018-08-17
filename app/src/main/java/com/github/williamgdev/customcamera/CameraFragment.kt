package com.github.williamgdev.customcamera


import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.github.williamgdev.customcamera.databinding.FragmentCameraBinding


class CameraFragment : Fragment() {
    private val TAG = "CameraFragment"

    private lateinit var binding: FragmentCameraBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        binding = FragmentCameraBinding.inflate(inflater, container, false)
        binding.button.setOnClickListener { showDiloag() }
        return binding.root
    }

    private val ACTION_REQUEST_GALLERY = 101

    private val ACTION_REQUEST_CAMERA = 102

    fun showDiloag() {
        val dialog = Dialog(requireActivity())
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Choose Image Source")
        builder.setItems(arrayOf<CharSequence>("Gallery", "Camera")) { dialog, which ->
            when (which) {
                0 -> {
                    val intent = Intent(
                            Intent.ACTION_GET_CONTENT)
                    intent.type = "image/*"

                    val chooser = Intent
                            .createChooser(
                                    intent,
                                    "Choose a Picture")
                    startActivityForResult(
                            chooser,
                            ACTION_REQUEST_GALLERY)
                }

                1 -> {
                    val cameraIntent = Intent(
                            android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    startActivityForResult(
                            cameraIntent,
                            ACTION_REQUEST_CAMERA)
                }

                else -> {
                }
            }
        }

        builder.show()
        dialog.dismiss()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == ACTION_REQUEST_GALLERY) {
                data?.data?.let { selectedImageUri ->
//                    val tempPath = JuiceAppUtility.getPath(
//                            selectedImageUri, activity)
                }
//                val bm = JuiceAppUtility
//                        .decodeFileFromPath(tempPath)
//                imgJuice.setImageBitmap(bm)
            } else if (requestCode == ACTION_REQUEST_CAMERA) {
                val photo = data?.extras?.get("data") as? Bitmap
                photo?.let {
//                    imgJuice.setImageBitmap(it)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = CameraFragment()

    }
}
