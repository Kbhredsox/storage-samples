/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.samples.mediastore

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _images = MutableLiveData<List<MediaStoreImage>>()
    val images: LiveData<List<MediaStoreImage>> get() = _images

    /**
     * Performs a one shot load of images from [MediaStore.Images.Media.EXTERNAL_CONTENT_URI] into
     * the [_images] [LiveData] above.
     */
    fun loadImages() {
        viewModelScope.launch {
            val imageList = queryImages()
            _images.postValue(imageList)
        }
    }

    private suspend fun queryImages(): List<MediaStoreImage> {
        val images = mutableListOf<MediaStoreImage>()

        /**
         * Working with [ContentResolver]s can be slow, so we'll do this off the main
         * thread inside a coroutine.
         */
        withContext(Dispatchers.IO) {

            /**
             * A key concept when working with Android [ContentProvider]s is something called
             * "projections". A projection is the list of columns to request from the provider,
             * and can be thought of (quite accurately) as the "SELECT ..." clause of a SQL
             * statement.
             *
             * It's not _required_ to provide a projection. In this case, one could pass `null`
             * in place of `projection` in the call to [ContentResolver.query], but requesting
             * more data than is required has a performance impact.
             *
             * For this sample, we only use a few columns of data, and so we'll request just a
             * subset of columns.
             */
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            )

            /**
             * The `selection` is the "WHERE ..." clause of a SQL statement. It's also possible
             * to omit this by passing `null` in its place, and then all rows will be returned.
             * In this case we're using a selection based on the date the image was taken.
             *
             * Note that we've included a `?` in our selection. This stands in for a variable
             * which will be provided by the next variable.
             */
            val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ?"

            /**
             * The `selectionArgs` is a list of values that will be filled in for each `?`
             * in the `selection`.
             */
            val selectionArgs = arrayOf(
                // Release day of the G1. :)
                dateToTimestamp(day = 22, month = 10, year = 2008).toString()
            )

            /**
             * Sort order to use. This can also be null, which will use the default sort
             * order. For [MediaStore.Images], the default sort order is ascending by date taken.
             */
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->

                /**
                 * In order to retrieve the data from the [Cursor] that's returned, we need to
                 * find which index matches each column that we're interested in.
                 *
                 * There are two ways to do this. The first is to use the method
                 * [Cursor.getColumnIndex] which returns -1 if the column ID isn't found. This
                 * is useful if the code is programmatically choosing which columns to request,
                 * but would like to use a single method to parse them into objects.
                 *
                 * In our case, since we know exactly which columns we'd like, and we know
                 * that they must be included (since they're all supported from API 1), we'll
                 * use [Cursor.getColumnIndexOrThrow]. This method will throw an
                 * [IllegalArgumentException] if the column named isn't found.
                 *
                 * In either case, while this method isn't slow, we'll want to cache the results
                 * to avoid having to look them up for each row.
                 */
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val displayNameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {

                    // Here we'll use the column indexs that we found above.
                    val id = cursor.getLong(idColumn)
                    val dateTaken = Date(cursor.getLong(dateTakenColumn))
                    val displayName = cursor.getString(displayNameColumn)

                    /**
                     * This is one of the trickiest parts:
                     *
                     * Since we're accessing images (using
                     * [MediaStore.Images.Media.EXTERNAL_CONTENT_URI], we'll use that
                     * as the base URI and append the ID of the image to it.
                     *
                     * This is the exact same way to do it when working with [MediaStore.Video] and
                     * [MediaStore.Audio] as well. Whatever `Media.EXTERNAL_CONTENT_URI` you
                     * query to get the items is the base, and the ID is the document to
                     * request there.
                     */
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val image = MediaStoreImage(id, displayName, dateTaken, contentUri)
                    images += image

                    // For debugging, we'll output the image objects we create to logcat.
                    Log.v(TAG, image.toString())
                }
            }
        }

        Log.v(TAG, "Found ${images.size} images")
        return images
    }

    /**
     * Convience method to convert a day/month/year date into a UNIX timestamp.
     *
     * We're suppressing the lint warning because we're not actually using the date formatter
     * to format the date to display, just to specify a format to use to parse it, and so the
     * locale warning doesn't apply.
     */
    @Suppress("SameParameterValue")
    @SuppressLint("SimpleDateFormat")
    private fun dateToTimestamp(day: Int, month: Int, year: Int): Long =
        SimpleDateFormat("dd.MM.yyyy").let { formatter ->
            formatter.parse("$day.$month.$year")?.time ?: 0
        }

}

private const val TAG = "MainActivityVM"