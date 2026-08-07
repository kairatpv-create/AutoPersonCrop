package com.example.a01

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.a01.crop.CropEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var folderName by remember { mutableStateOf("Не выбрана") }
    var imageCount by remember { mutableIntStateOf(0) }

    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->

            if (uri == null) return@rememberLauncherForActivityResult

            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            folderUri = uri

            val folder =
                DocumentFile.fromTreeUri(context, uri)

            folderName =
                folder?.name ?: "Неизвестная"

            imageCount = 0

            folder?.listFiles()?.forEach {

                val name =
                    it.name?.lowercase() ?: ""

                if (
                    name.endsWith(".jpg") ||
                    name.endsWith(".jpeg") ||
                    name.endsWith(".png") ||
                    name.endsWith(".webp") ||
                    name.endsWith(".heic")
                ) {
                    imageCount++
                }

            }

        }

    Surface(
        modifier = Modifier.fillMaxSize()
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                "AutoPersonCrop",
                style = MaterialTheme.typography.headlineMedium
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    folderPicker.launch(null)
                }
            ) {
                Text("Выбрать папку")
            }

            HorizontalDivider()

            Text("Папка:")

            Text(folderName)

            HorizontalDivider()

            Text("Найдено фотографий:")

            Text(
                imageCount.toString(),
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(
                modifier = Modifier.weight(1f)
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = folderUri != null,
                onClick = {

                    folderUri?.let { uri ->

                        scope.launch(Dispatchers.IO) {

                            val result =
                                CropEngine.processFolder(
                                    context,
                                    uri
                                )

                            withContext(Dispatchers.Main) {

                                Toast.makeText(
                                    context,
                                    "Обработано $result фотографий",
                                    Toast.LENGTH_LONG
                                ).show()

                            }

                        }

                    }

                }
            ) {

                Text("НАЧАТЬ ОБРАБОТКУ")

            }

        }

    }

}