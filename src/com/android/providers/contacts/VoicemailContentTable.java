/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.providers.contacts;

import static com.android.providers.contacts.util.DbQueryUtils.checkForSupportedColumns;
import static com.android.providers.contacts.util.DbQueryUtils.concatenateClauses;
import static com.android.providers.contacts.util.DbQueryUtils.getEqualityClause;

import com.android.providers.contacts.VoicemailContentProvider.UriData;
import com.android.providers.contacts.util.CloseUtils;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.CallLog.Calls;
import android.provider.VoicemailContract;
import android.provider.VoicemailContract.Voicemails;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Implementation of {@link VoicemailTable.Delegate} for the voicemail content table.
 */
public class VoicemailContentTable implements VoicemailTable.Delegate {
    private static final String TAG = "VoicemailContentProvider";
    // Voicemail projection map
    private static final ProjectionMap sVoicemailProjectionMap = new ProjectionMap.Builder()
            .add(Voicemails._ID)
            .add(Voicemails.NUMBER)
            .add(Voicemails.DATE)
            .add(Voicemails.DURATION)
            .add(Voicemails.NEW)
            .add(Voicemails.IS_READ)
            .add(Voicemails.STATE)
            .add(Voicemails.SOURCE_DATA)
            .add(Voicemails.SOURCE_PACKAGE)
            .add(Voicemails.HAS_CONTENT)
            .add(Voicemails.MIME_TYPE)
            .add(Voicemails._DATA)
            .build();

    /** The private directory in which to store the data associated with the voicemail. */
    private static final String DATA_DIRECTORY = "voicemail-data";

    private static final String[] MIME_TYPE_ONLY_PROJECTION = new String[] { Voicemails.MIME_TYPE };
    private static final String[] FILENAME_ONLY_PROJECTION = new String[] { Voicemails._DATA };

    private final String mTableName;
    private final SQLiteOpenHelper mDbHelper;
    private final Context mContext;
    private final VoicemailTable.DelegateHelper mDelegateHelper;

    public VoicemailContentTable(String tableName, Context context, SQLiteOpenHelper dbHelper,
            VoicemailTable.DelegateHelper contentProviderHelper) {
        mTableName = tableName;
        mContext = context;
        mDbHelper = dbHelper;
        mDelegateHelper = contentProviderHelper;
    }

    @Override
    public int bulkInsert(UriData uriData, ContentValues[] valuesArray) {
        int numInserted = 0;
        for (ContentValues values : valuesArray) {
            if (insertInternal(uriData, values, false) != null) {
                numInserted++;
            }
        }
        if (numInserted > 0) {
            mDelegateHelper.notifyChange(uriData.getUri(), Intent.ACTION_PROVIDER_CHANGED);
        }
        return numInserted;
    }

    @Override
    public Uri insert(UriData uriData, ContentValues values) {
        return insertInternal(uriData, values, true);
    }

    private Uri insertInternal(UriData uriData, ContentValues values,
            boolean sendProviderChangedNotification) {
        checkForSupportedColumns(sVoicemailProjectionMap, values);
        ContentValues copiedValues = new ContentValues(values);
        checkInsertSupported(uriData);
        mDelegateHelper.checkAndAddSourcePackageIntoValues(uriData, copiedValues);

        // "_data" column is used by base ContentProvider's openFileHelper() to determine filename
        // when Input/Output stream is requested to be opened.
        copiedValues.put(Voicemails._DATA, generateDataFile());

        // call type is always voicemail.
        copiedValues.put(Calls.TYPE, Calls.VOICEMAIL_TYPE);

        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        long rowId = db.insert(mTableName, null, copiedValues);
        if (rowId > 0) {
            Uri newUri = ContentUris.withAppendedId(uriData.getUri(), rowId);
            mDelegateHelper.notifyChange(newUri, VoicemailContract.ACTION_NEW_VOICEMAIL);
            if (sendProviderChangedNotification) {
                mDelegateHelper.notifyChange(newUri, Intent.ACTION_PROVIDER_CHANGED);
            }
            // Populate the 'voicemail_uri' field to be used by the call_log provider.
            updateVoicemailUri(db, newUri);
            return newUri;
        }
        return null;
    }

    private void checkInsertSupported(UriData uriData) {
        if (uriData.hasId()) {
            throw new UnsupportedOperationException(String.format(
                    "Cannot insert URI: %s. Inserted URIs should not contain an id.",
                    uriData.getUri()));
        }
    }

    /** Generates a random file for storing audio data. */
    private String generateDataFile() {
        try {
            File dataDirectory = mContext.getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
            File voicemailFile = File.createTempFile("voicemail", "", dataDirectory);
            return voicemailFile.getAbsolutePath();
        } catch (IOException e) {
            // If we are unable to create a temporary file, something went horribly wrong.
            throw new RuntimeException("unable to create temp file", e);
        }
    }
    private void updateVoicemailUri(SQLiteDatabase db, Uri newUri) {
        ContentValues values = new ContentValues();
        values.put(Calls.VOICEMAIL_URI, newUri.toString());
        // Directly update the db because we cannot update voicemail_uri through external
        // update() due to projectionMap check. This also avoids unnecessary permission
        // checks that are already done as part of insert request.
        db.update(mTableName, values, UriData.createUriData(newUri).getWhereClause(), null);
    }

    @Override
    public int delete(UriData uriData, String selection, String[] selectionArgs) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());

        // Delete all the files associated with this query.  Once we've deleted the rows, there will
        // be no way left to get hold of the files.
        Cursor cursor = null;
        try {
            cursor = query(uriData, FILENAME_ONLY_PROJECTION, selection, selectionArgs, null);
            while (cursor.moveToNext()) {
                File file = new File(cursor.getString(0));
                if (file.exists()) {
                    boolean success = file.delete();
                    if (!success) {
                        Log.e(TAG, "Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        } finally {
            CloseUtils.closeQuietly(cursor);
        }

        // Now delete the rows themselves.
        int count = db.delete(mTableName, combinedClause, selectionArgs);
        if (count > 0) {
            mDelegateHelper.notifyChange(uriData.getUri(), Intent.ACTION_PROVIDER_CHANGED);
        }
        return count;
    }

    @Override
    public Cursor query(UriData uriData, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(mTableName);
        qb.setProjectionMap(sVoicemailProjectionMap);
        qb.setStrict(true);

        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, combinedClause, selectionArgs, null, null, sortOrder);
        if (c != null) {
            c.setNotificationUri(mContext.getContentResolver(), Voicemails.CONTENT_URI);
        }
        return c;
    }

    @Override
    public int update(UriData uriData, ContentValues values, String selection,
            String[] selectionArgs) {
        checkForSupportedColumns(sVoicemailProjectionMap, values);
        checkUpdateSupported(uriData);
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        // TODO: This implementation does not allow bulk update because it only accepts
        // URI that include message Id. I think we do want to support bulk update.
        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());
        int count = db.update(mTableName, values, combinedClause, selectionArgs);
        if (count > 0) {
            mDelegateHelper.notifyChange(uriData.getUri(), Intent.ACTION_PROVIDER_CHANGED);
        }
        return count;
    }

    private void checkUpdateSupported(UriData uriData) {
        if (!uriData.hasId()) {
            throw new UnsupportedOperationException(String.format(
                    "Cannot update URI: %s.  Bulk update not supported", uriData.getUri()));
        }
    }

    @Override
    public String getType(UriData uriData) {
        // TODO: DB lookup for the mime type may cause strict mode exception for the callers of
        // getType(). See if this could be avoided.
        if (uriData.hasId()) {
            // An individual voicemail - so lookup the MIME type in the db.
            return lookupMimeType(uriData);
        }
        // Not an individual voicemail - must be a directory listing type.
        return Voicemails.DIR_TYPE;
    }

    /** Query the db for the MIME type of the given URI, called only from getType(). */
    private String lookupMimeType(UriData uriData) {
        Cursor cursor = null;
        try {
            // Use queryInternal, bypassing provider permission check. This is needed because
            // getType() can be called from any application context (even without voicemail
            // permissions) to know the MIME type of the URI. There is no security issue here as we
            // do not expose any sensitive data through this interface.
            cursor = query(uriData, MIME_TYPE_ONLY_PROJECTION, null, null, null);
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndex(Voicemails.MIME_TYPE));
            }
        } finally {
            CloseUtils.closeQuietly(cursor);
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(UriData uriData, String mode)
            throws FileNotFoundException {
        ParcelFileDescriptor fileDescriptor = mDelegateHelper.openDataFile(uriData, mode);
        // If the open succeeded, then update the has_content bit in the table.
        if (mode.contains("w")) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Voicemails.HAS_CONTENT, 1);
            update(uriData, contentValues, null, null);
        }
        return fileDescriptor;
    }

    /** Creates a clause to restrict the selection to only voicemail call type.*/
    private String getCallTypeClause() {
        return getEqualityClause(Calls.TYPE, String.valueOf(Calls.VOICEMAIL_TYPE));
    }
}